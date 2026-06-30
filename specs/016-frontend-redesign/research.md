# Research: Frontend Redesign — "Artifact Sanctuary"

Phase 0 design decisions. No `NEEDS CLARIFICATION` remained from the spec (the three scope decisions
were resolved with the user). The items below resolve the *how* for planning, per phase.

## D1. Theme as CSS custom properties, not a framework (Phase 1)

- **Decision**: Encode the mockup's design language as CSS custom properties in one global stylesheet
  (`frontend/src/lib/theme/tokens.css`) loaded by the root layout, referenced from each component's
  scoped `<style>`. Tokens: surface ramp (`--rq-bg:#080503`, `--rq-panel:#160e06`, `--rq-panel-2:#241a0a`),
  accent (`--rq-gold:#C9A227`), text ramp (`--rq-text:#c8a840`, `--rq-muted:#8B6914`, `--rq-dim:#7a5c14`),
  borders (`--rq-border:#3a2810`), heading font `Cinzel`, mono `ui-monospace`.
- **Rationale**: The current UI uses scoped Svelte CSS with hex literals and no framework; tokens are the
  smallest change that makes the restyle consistent and themeable. Introducing Tailwind/UnoCSS would be a
  larger, unrelated migration and a new build dependency.
- **Alternatives rejected**: Tailwind (new toolchain + churn for no functional gain); inline styles like
  the mockup (unmaintainable, no reuse).
- **Font loading**: `Cinzel` via the bundled-or-self-hosted web font; CI builds must not depend on a live
  Google Fonts fetch, so self-host the `Cinzel` woff2 under `frontend/static` (the existing UiController
  already serves `woff/woff2`).

## D2. App shell in the root layout (Phase 1)

- **Decision**: `+layout.svelte` becomes the `AppShell` host: a fixed left `Sidebar` (Dashboard,
  Repositories, Publish, Users & Tokens, Settings) + a `Topbar` (section title, optional search slot,
  version). Active section derives from `$page.route`/`$page.url`. Login renders **outside** the shell
  (full-screen) when unauthenticated; the shell renders when a session exists.
- **Rationale**: Matches the mockup's single-page shell and keeps routing/SSR config (`ssr=false`) as-is.
- **Alternatives rejected**: A separate shell component wrapped per-route (duplicates layout, fights
  SvelteKit's layout model).

## D3. Stats reuse the existing storage-usage source (Phase 2)

- **Decision**: `GET /api/stats` returns `{ repositories, artifacts, storageBytes }`. `repositories` =
  registry size; `artifacts` and `storageBytes` come from the **same component that already feeds** the
  `relikquary.storage.objects` and `relikquary.storage.usage.bytes` Micrometer gauges (a periodically
  refreshed snapshot, `relikquary.observability.storage-usage-refresh`, default 5m). The endpoint reads
  the cached snapshot — it does **not** walk storage per request.
- **Rationale**: Zero new dependency, no new expensive scan, and the "near-real-time" precision is already
  what the gauges provide (spec Assumption). Authorization: open to read like the other `/api` browse
  endpoints (the figures are non-sensitive aggregates).
- **Alternatives rejected**: Exposing `/actuator/prometheus` to the UI (PUBLISH-gated, scrape format, not
  a UI contract); a fresh full walk per load (cost, and redundant with the gauge source).

## D4. Catalog aggregation over `ArtifactStorage.walk`, bounded (Phase 2)

- **Decision**: `GET /api/catalog?q=&repo=&page=` returns aggregated rows keyed by `group:artifact`
  (latest version, version count, aggregate size). Computed by walking artifact directories and grouping
  by coordinate, reusing the version-ordering already used by hosted-metadata generation for "latest".
  Bounded by **pagination** and a **short-TTL in-memory cache** (per repo) so a large repo is not walked
  on every keystroke; `q` filters server-side by `group:artifact` substring.
- **Rationale**: The browse API only lists one directory level; the catalog needs cross-version
  aggregation. Reusing `walk` + the existing version ordering avoids new storage concepts. Proxy repos
  reflect cached content only (documented in the response/UI).
- **Alternatives rejected**: A persisted index (premature; adds write-path coupling and a migration before
  Phase 3 even introduces a DB); unbounded walk per request (DoS-ish on large repos).
- **Open for /speckit-plan refinement**: cache TTL + invalidation (publish could bust the per-coordinate
  entry), and whether search is a separate `/api/catalog/search` or the `q` param (leaning `q`).

## D5. Persistence: Spring Data JPA + Flyway, one selectable `DataSource` (Phase 3)

- **Decision**: A single relational `DataSource` selected by `relikquary.persistence.backend = sqlite |
  postgres` (default `sqlite`, file under the work/storage dir). Spring Data JPA repositories;
  **Flyway** migrations under `db/migration` create/evolve schema on boot for both engines. Stateless
  Phase-1/2 features must keep working when persistence is down (FR-020) — the admin controllers fail soft
  (503 + themed error), they don't break app startup hard if the DB is briefly unreachable post-boot.
- **Rationale**: Standard, well-trodden Spring Boot persistence; Flyway gives versioned, engine-portable
  schema across SQLite and Postgres; JPA keeps the two engines behind one repository API.
- **Alternatives rejected**: Raw JDBC + hand-rolled DDL (re-implements migrations/dialects for two
  engines); jOOQ (codegen + heavier for a handful of small tables); an embedded KV store (no relational
  queries for activity/listing, and still a new dep).
- **Supply chain (Principle IV)**: new coordinates — `spring-boot-starter-data-jpa`, `flyway-core`
  (+ `flyway-database-postgresql` if required by the Flyway version), `org.xerial:sqlite-jdbc`, a
  SQLite Hibernate dialect (e.g. `org.hibernate.community:hibernate-community-dialects`), and
  `org.postgresql:postgresql` — each MUST be pinned in `gradle/verification-metadata.xml` and declared in
  `gradle/libs.versions.toml` in the same change.

## D6. API tokens layered onto the existing auth (Phase 3)

- **Decision**: Tokens are opaque random secrets (e.g. `rlq_` + high-entropy body); only a salted hash is
  stored. Authentication accepts a token **in addition to** existing Basic users — presented either as the
  Basic password for a sentinel/username or as a bearer credential — resolved by a Spring Security
  authentication provider that checks the token table, scope, and revoked state, and updates `lastUsed`.
  Scope (`read` | `publish`) maps onto the existing `Action`/`RepositoryAuthorizer` decision.
- **Rationale**: Keeps the publish/resolve contract additive (Principle I): existing clients/credentials
  are unchanged; tokens are a new accepted credential. Hash-at-rest + one-time reveal meets FR-015.
- **Alternatives rejected**: Signed/JWT tokens (no server-side revocation without a denylist — defeats the
  revoke requirement); storing the secret reversibly (violates one-time-reveal/secret-at-rest).
- **Open for /speckit-plan refinement**: exact wire presentation (Basic-password vs. `Authorization:
  Bearer`) and the username sentinel, decided against what Maven/Gradle clients send most cleanly.

## D7. Managed users compose with config users (Phase 3)

- **Decision**: A JPA-backed `UserDetailsService` is composed with the existing
  `InMemoryUserDetailsManager` (config users) via a delegating/compositeresolver — config users resolve
  first (or by precedence rule), managed users from the DB second. Admin CRUD writes the DB only; the
  static YAML users are never mutated. Roles reuse the existing role model.
- **Rationale**: FR-016 / US8-scenario-4: no lockout, both authenticate during/after transition.
- **Alternatives rejected**: Replacing config users outright (breaks existing deployments and the
  bootstrap admin); migrating config users into the DB on boot (surprising, and couples config to DB
  state).

## D8. Publish history recorded post-commit, attribution-safe (Phase 3)

- **Decision**: After a **successful** store in `RepositoryController.publish`, record a `PublishEvent`
  (coordinate, principal-or-null, timestamp). Recording is best-effort and MUST NOT alter or fail the
  publish if the DB write fails (Principle IV — faithful storage; publish success is independent of
  history). `GET /api/activity` serves recent events; artifact detail reads the latest event for its
  coordinate. Anonymous publishes store `principal = null` and render as "unknown".
- **Rationale**: Provenance without touching stored bytes or the publish contract; decoupled failure.
- **Alternatives rejected**: Deriving "published by" from storage (not tracked — the gap that motivated
  this); blocking publish on history write (couples artifact integrity to an unrelated table).

## D9. Runtime settings are a bounded allow-list (Phase 3)

- **Decision**: Only operationally-safe keys become runtime-editable `Setting` rows (e.g. display/server
  identity, auth-policy toggles surfaced in the mockup). Storage backend, repository topology, security
  infrastructure, and persistence config remain static YAML/env. Admin-only, validated, persisted.
- **Rationale**: Avoids turning every static knob into a live, security-sensitive surface (spec
  Assumption: bounded settings); keeps Principle I config contracts intact for the critical knobs.
- **Alternatives rejected**: A generic "edit any config" screen (security and contract risk; some changes
  require restart anyway).
