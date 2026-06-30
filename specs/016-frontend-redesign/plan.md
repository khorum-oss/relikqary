# Implementation Plan: Frontend Redesign — "Artifact Sanctuary"

**Branch**: `016-frontend-redesign` | **Date**: 2026-06-30 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/016-frontend-redesign/spec.md`

## Summary

Re-skin the SvelteKit UI to the "Artifact Sanctuary" mockup and grow it in three independently
shippable phases. **Phase 1** is a pure frontend reskin (sidebar shell + dark "vault" theme tokens) over
the existing browse/upload/download/delete/snippet APIs — no backend change. **Phase 2** adds two small,
read-only JSON endpoints — a stats summary (repo count, object count, storage bytes) reusing the existing
storage-usage source, and an artifact-catalog aggregation (`group:artifact` → latest/size/version-count)
computed from `ArtifactStorage.walk` — to power a real Dashboard, a cross-repo catalog, and topbar
search; the Repositories screen offers both the catalog and the existing raw folder/tree view. **Phase 3**
introduces the project's first durable state behind an operator-selectable backend (embedded SQLite by
default, optional external Postgres) via Spring Data JPA + Flyway, then builds API tokens (issue/list/
revoke, scoped), managed users, publish-event history/attribution, and a bounded runtime-settings screen
on top of it. Maven/Gradle publish/resolve, per-repo authorization, hosted/proxy/group semantics, and the
standalone-or-bundled deployment model are unchanged throughout.

## Technical Context

**Language/Version**: Frontend — TypeScript + Svelte 5 (runes) on SvelteKit, Vite. Backend — Kotlin
2.3.21 / JDK 21, Spring Boot 4.1.0.

**Primary Dependencies**: Existing. Phase 1: **no new dependency** (frontend-only; reuse `api.ts`,
`auth.svelte.ts`, existing components). Phase 2: **no new dependency** (new read endpoints reuse
`ArtifactStorage`, the storage-usage gauge source, and the repository registry). Phase 3: **new deps,
gated to this phase** — Spring Data JPA (`spring-boot-starter-data-jpa`), Flyway (schema migration), a
SQLite JDBC driver + Hibernate community dialect, and the PostgreSQL driver. Each new coordinate MUST be
added to `gradle/verification-metadata.xml` (Principle IV); dependency verification stays enabled.

**Storage**: Artifacts — unchanged (`ArtifactStorage`: filesystem / S3). Application state (Phase 3 only)
— relational, behind a single configurable `DataSource`: embedded SQLite file (default, zero-config) or
external Postgres (`relikquary.persistence.*`). No artifact bytes ever move into the database.

**Testing**: Frontend — Vitest/Testing-Library + the existing Storybook stories; Playwright e2e against a
real backend (the existing `frontend/scripts/e2e.sh` harness in CI). Backend — JUnit 5 + `@SpringBootTest`;
Phase 3 persistence exercised against **both** real engines (in-memory/file SQLite for the default path,
**Testcontainers Postgres** for the external path — Principle II forbids mocking the storage boundary);
real Maven + Gradle round-trips proving a scoped **token** authenticates publish/resolve and that a
revoked token is rejected.

**Target Platform**: Linux server (Spring Boot fat jar), modern browsers for the SPA.

**Project Type**: Web application — `backend/` (Kotlin service) + `frontend/` (SvelteKit SPA, served
standalone or bundled into the backend jar under `classpath:/static/ui/`).

**Performance Goals**: Reskin keeps current responsiveness. Stats endpoint is O(1)-ish (reuses the
periodically-refreshed usage source; no full walk per request). Catalog aggregation is bounded and cached
/ paginated so a large repo doesn't force an unbounded walk on every request. Phase-3 admin queries are
small-table lookups.

**Constraints**: detekt zero + Kover hold (Principle III); dependency verification stays enabled and any
new dep is pinned in `verification-metadata.xml` (Principle IV); no change to repository layout, the
publish/resolve protocol, or existing config contracts (Principle I) — all new HTTP surface is additive
under `/api`, and persistence config is additive and optional.

**Scale/Scope**: One backend module (`backend/`) + the `frontend/` SPA. ~2 new read endpoints (Phase 2),
~4 new admin resource groups + a persistence layer (Phase 3), and a frontend restructure into a shell +
restyled/new routes. Mockup screen count: 7.

## Constitution Check

*GATE: re-check after Phase 1 design.*

- **I. Repository Contract & Client Compatibility** — PASS. No change to served layout, `maven-metadata`,
  checksums, Gradle Module conventions, or the publish/resolve HTTP protocol. New JSON endpoints are
  **additive** under `/api/*`; the SPA route changes are internal. New persistence config
  (`relikquary.persistence.*`) is additive and optional (embedded default), so no existing configuration
  contract is removed or renamed. **No MAJOR bump.** Phase 3 introduces token auth as an *additional*
  credential type alongside existing Basic auth — purely additive to the publish/resolve contract.
- **II. Test-First & Integration-Verified** — PASS (planned). Phase-1 reskin is covered by the existing UI
  unit/Storybook tests plus Playwright e2e against a real backend. Phase-2 endpoints get integration tests
  asserting figures/aggregations against real stored state. Phase-3 token auth is proven by **real Maven
  and Gradle** publish/resolve round-trips (issue → use → revoke → rejected), and persistence is tested
  against real SQLite and real Postgres (Testcontainers), never mocked.
- **III. Quality Gates** — PASS (planned). New Kotlin obeys detekt/Kover; new frontend obeys its
  check/build. SonarCloud not regressed.
- **IV. Supply-Chain Integrity & Faithful Storage** — PASS, with required follow-through. Stored artifact
  bytes, checksums, and signatures are untouched; publish-event recording observes publishes, it does not
  rewrite them. **Every new Phase-3 dependency (JPA, Flyway, SQLite + dialect, Postgres driver) MUST be
  added to `gradle/verification-metadata.xml`** in the same change that introduces it, or the build fails
  by design — this is the one non-trivial supply-chain task and is tracked in Complexity Tracking.

No principle violations. New dependencies in Phase 3 are justified below (Complexity Tracking) and are the
minimal set for an operator-selectable relational store.

## Project Structure

### Documentation (this feature)

```text
specs/016-frontend-redesign/
├── spec.md              # Feature specification (done)
├── plan.md              # This file
├── research.md          # Phase 0 — decisions: theme tokens, catalog computation, DB/ORM/migration choice
├── data-model.md        # Phase 1 design — Phase-3 entities (Token, User, PublishEvent, Setting) + DTOs
├── quickstart.md        # Per-phase manual verification (reskin walkthrough; stats/catalog; token round-trip)
├── contracts/           # New /api contracts: stats, catalog/search, tokens, users, settings
└── tasks.md             # /speckit-tasks output (NOT created here)
```

### Source Code (repository root)

```text
backend/src/main/kotlin/org/khorum/oss/relikquary/
├── protocol/
│   ├── BrowseController.kt          # exists — browse/file/module/delete (Phase 1 reuse)
│   ├── StatsController.kt           # NEW (Phase 2) — GET /api/stats
│   ├── CatalogController.kt         # NEW (Phase 2) — GET /api/catalog, /api/catalog/search
│   └── admin/                       # NEW (Phase 3)
│       ├── TokenController.kt       #   POST/GET/DELETE /api/admin/tokens
│       ├── UserController.kt        #   GET/POST/PATCH/DELETE /api/admin/users
│       ├── SettingsController.kt    #   GET/PUT /api/admin/settings
│       └── ActivityController.kt    #   GET /api/activity (recent publishes)
├── catalog/                         # NEW (Phase 2) — aggregation service over ArtifactStorage.walk
├── persistence/                     # NEW (Phase 3) — DataSource selection, JPA config, Flyway
│   └── migration (resources/db/migration/*.sql)
├── security/                        # exists — extend with token authentication filter (Phase 3)
└── observability/metrics/           # exists — storage-usage source reused by StatsController (Phase 2)

frontend/src/
├── lib/
│   ├── theme/                       # NEW (Phase 1) — vault tokens (colors, fonts, spacing), global css
│   ├── api.ts                       # extend (Phase 2/3) — stats, catalog, tokens, users, settings, activity
│   ├── auth.svelte.ts               # exists — session (Phase 1 reuse)
│   └── components/
│       ├── shell/                   # NEW (Phase 1) — Sidebar, Topbar, AppShell
│       ├── (existing components)    # restyled in place: FileListing, FileDetailsPanel, UploadForm,
│       │                            #   ConsumeSnippets, ModuleDetail, KindBadge, Breadcrumbs, …
│       ├── catalog/                 # NEW (Phase 2) — CatalogTable, SearchBox
│       ├── dashboard/               # NEW (Phase 2) — StatCards, RecentPublishes (P3-fed)
│       └── admin/                   # NEW (Phase 3) — TokensTable, UsersTable, SettingsForm
└── routes/
    ├── +layout.svelte               # becomes the AppShell host (sidebar + topbar)
    ├── +page.svelte                 # Dashboard (Phase 2 data; Phase 1 placeholder)
    ├── repositories/                # catalog view + drill-in; toggles to raw folder view
    ├── r/[repo]/[...path]/          # exists — raw folder/tree browse (restyled, kept)
    ├── publish/                     # Publish screen (snippet + drop zone; Phase 3 token panel)
    ├── users/                       # Users & Tokens (Phase 3)
    └── settings/                    # Settings (Phase 3)
```

**Structure Decision**: Web-application layout (existing `backend/` + `frontend/`). Phase 1 is confined to
`frontend/` (new `theme/` and `shell/`, restyle-in-place of existing components, route reshaping around the
shell). Phase 2 adds a `catalog/` service + two thin controllers in `backend/` and `catalog/`+`dashboard/`
UI. Phase 3 adds `persistence/` and `protocol/admin/` in `backend/`, a token auth path in `security/`, and
`admin/` UI. New backend HTTP surface is namespaced under `/api` (and `/api/admin` for privileged actions)
to keep it clearly separate from the Maven protocol routes.

## Phase Approach (technical)

### Phase 1 — Reskin (frontend only, no backend change)

- Extract the mockup's design language into **theme tokens** (CSS custom properties): palette
  (`#080503`/`#160e06` surfaces, `#C9A227` accent, bronze text ramps), `Cinzel` headings + monospace
  coordinates, borders/corner-accents, hover states. One global stylesheet + per-component scoped styles
  referencing the tokens — no Tailwind introduced.
- Build the **AppShell** (`Sidebar` + `Topbar`) in `+layout.svelte`; move nav from the current top header
  into the sidebar (Dashboard/Repositories/Publish/Users & Tokens/Settings), active-state from the route.
- Restyle existing screens against current APIs: login, repositories list, `r/[repo]/[...path]` raw
  browse, `FileDetailsPanel`, `UploadForm` (add drag-and-drop drop zone), `ConsumeSnippets`,
  `ModuleDetail`. Users & Tokens / Settings render as **disabled/placeholder** sections until Phase 3.
- Keep `auth.svelte.ts` and `api.ts` behavior; only presentation changes. Existing Storybook stories +
  Playwright e2e are updated to the new markup (SC-003: parity verified by tests).

### Phase 2 — Cheap read-only data

- `GET /api/stats` → `{ repositories, artifacts, storageBytes }`. Repo count from the registry; artifact
  count + bytes from the **existing storage-usage source** that already backs `relikquary.storage.objects`
  / `relikquary.storage.usage.bytes` gauges (reuse, do not re-walk per request). Open to read like the
  other `/api` browse endpoints.
- `GET /api/catalog` (+ `?q=` search) → aggregated `group:artifact` rows (latest version via the same
  version ordering hosted-metadata uses, aggregate size, version count). Computed from
  `ArtifactStorage.walk` over artifact dirs, **bounded** (pagination + a short-TTL cache) so large repos
  don't trigger unbounded walks. Proxy repos reflect cached content only (documented).
- Frontend: real `StatCards` on the Dashboard; `CatalogTable` + topbar `SearchBox` as the Repositories
  default view, with a per-repo toggle to the Phase-1 raw folder view.

### Phase 3 — Stateful admin (depends on persistence)

- **Persistence foundation first** (US6 is the enabler): a single `DataSource` chosen by
  `relikquary.persistence.backend = sqlite | postgres` (default `sqlite`, file under the storage/work
  dir); Spring Data JPA repositories; **Flyway** migrations create schema on first boot. Stateless
  Phase-1/2 features must keep working if persistence is down (FR-020) — admin screens fail soft.
- **API tokens**: random secret shown once; persisted only as a salted hash. A Spring Security
  authentication path accepts a token (e.g., as the Basic password or a bearer) **in addition to** existing
  Basic users; scope governs read vs publish. Revocation flips state; `lastUsed` updated on use.
- **Managed users**: JPA-backed `UserDetailsService` composed with the existing
  `InMemoryUserDetailsManager` (config users keep working — FR-016) so both resolve; admin CRUD + roles.
- **Publish history**: hook the publish path (`RepositoryController.publish`) to record a `PublishEvent`
  (coordinate, principal, timestamp) **after** successful storage — never altering stored bytes; feeds
  `GET /api/activity` (Recent Publishes) and artifact-detail attribution. Anonymous publishes record
  unknown, not fabricated (FR-017 / US9 scenario 3).
- **Runtime settings**: a small allow-list of safe keys persisted as `Setting` rows, admin-only, validated;
  storage/repository-topology/security-infra stay static config (Assumption: bounded settings).

## Complexity Tracking

Phase 3 adds dependencies and the project's first datastore — a deliberate, spec-approved departure from
the stateless design. Justified here per Principle IV/governance:

| Addition | Why Needed | Simpler Alternative Rejected Because |
|----------|-----------|--------------------------------------|
| Relational datastore (Phase 3) | Tokens, managed users, publish history, runtime settings need durable, queryable, concurrently-written state | Flat files / config can't safely handle concurrent writes, secret hashing + revocation, or activity queries; the user explicitly chose real persistence |
| Operator-selectable SQLite **and** Postgres | SQLite gives zero-config single-node default; Postgres supports shared/HA deployments | Picking only one forces either a server dependency on small installs or no HA story on large ones; the user asked for both |
| Spring Data JPA + Flyway | Standard Spring Boot persistence + versioned, auto-applied schema across both engines | Hand-rolled JDBC/DDL would re-implement migrations and dialect handling and complicate the two-engine requirement |
| New pinned deps in `verification-metadata.xml` | Dependency verification stays enabled (Principle IV) | Disabling/loosening verification is forbidden by the constitution; the only correct path is to pin the new artifacts |

## Deferred to later steps

- **research.md** (Phase 0): finalize theme-token taxonomy; catalog computation/caching strategy and
  pagination shape; ORM/migration/dialect specifics and the exact SQLite+Hibernate combo; token format
  (opaque-hashed vs. signed) and how it rides existing Basic auth vs. a bearer scheme.
- **data-model.md / contracts/** (Phase 1 design): concrete schemas for Token, User, PublishEvent,
  Setting, and the JSON shapes for `/api/stats`, `/api/catalog`, `/api/activity`, `/api/admin/*`.
- **tasks.md**: generated via `/speckit-tasks`, grouped by the three phases (P1/P2/P3) so each phase is an
  independently deliverable, testable slice.
