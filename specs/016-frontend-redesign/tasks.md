---
description: "Task list for the Artifact Sanctuary frontend redesign"
---

# Tasks: Frontend Redesign — "Artifact Sanctuary"

**Input**: Design documents from `specs/016-frontend-redesign/`

**Prerequisites**: spec.md, plan.md, research.md, data-model.md, contracts/frontend-redesign.md

**Tests**: Required — Principle II mandates test-first (Red → Green) and real client/storage round-trips
for any change to repository serving, auth, or a storage backend. Frontend changes are covered by
Storybook/Vitest unit tests and the Playwright e2e harness against a real backend.

**Organization**: Tasks are grouped by phase (P1 → P2 → P3); each phase is an independently shippable
slice. Within a phase, Foundational tasks block the user-story tasks. `[P]` marks tasks with no shared
file/dependency that may run in parallel.

## Path Conventions

- Backend main: `backend/src/main/kotlin/org/khorum/oss/relikquary/`; tests:
  `backend/src/test/kotlin/org/khorum/oss/relikquary/`; resources: `backend/src/main/resources/`.
- Frontend: `frontend/src/` (`lib/`, `lib/components/`, `routes/`); tests in `frontend/tests/` and
  `*.stories.svelte` beside components.
- Dependency pins: `gradle/libs.versions.toml` + `gradle/verification-metadata.xml`.

---

## Phase A: Setup

- [ ] T001 Confirm Phase-1/2 add **no** backend dependency; enumerate touchpoints: existing `api.ts`,
  `auth.svelte.ts`, `+layout.svelte`, `routes/+page.svelte`, `routes/r/[repo]/[...path]/+page.svelte`, and
  the component set under `lib/components/`. Capture the mockup token values from
  `frontend/planning/Relikquary UI.dc.html`.
- [ ] T002 [P] Self-host the `Cinzel` web font under `frontend/static/` (no live Google Fonts fetch in CI);
  verify `UiController` serves `woff2`.

---

## Phase 1 — Reskin (P1, no backend change)

### Foundational (blocks all P1 stories)

- [ ] T010 Create `frontend/src/lib/theme/tokens.css` with the vault palette/type/border tokens (D1) and
  import it in the root layout; add a `tokens` Storybook story documenting them.
- [ ] T011 Build `lib/components/shell/Sidebar.svelte`, `Topbar.svelte`, `AppShell.svelte` (D2) with
  active-section state from the route; stories for each.

### User Story 1 — Themed shell & navigation (P1)

- [ ] T020 Restructure `routes/+layout.svelte` to host `AppShell`; render full-screen login when
  unauthenticated, shell when a session exists; wire sign-out.
- [ ] T021 [P] Restyle `LoginForm.svelte` to the themed full-screen login; update its story.
- [ ] T022 e2e/unit: login → shell appears; sidebar navigation updates active item + topbar title + route;
  sign-out returns to login (US1 acceptance scenarios). **Checkpoint: US1 demoable.**

### User Story 2 — Restyled artifact browsing (P1)

- [ ] T030 [P] Restyle `RepositoryRow.svelte` + repositories list (`routes/+page.svelte` or
  `routes/repositories/`) with `KindBadge`.
- [ ] T031 [P] Restyle `Breadcrumbs.svelte` + `FileListing.svelte` (raw folder view) in
  `routes/r/[repo]/[...path]/`.
- [ ] T032 [P] Restyle `FileDetailsPanel.svelte` (size, last-modified, checksums, download).
- [ ] T033 [P] Restyle `ConsumeSnippets.svelte`, `ModuleDetail.svelte`, `GradleModuleBadge.svelte`,
  `EmptyState.svelte`, `ErrorBanner.svelte`.
- [ ] T034 Verify delete + 401/403/404 handling still work in the new theme; update e2e for browse →
  details → download → delete (US2). **Checkpoint: US2 demoable.**

### User Story 3 — Restyled publish & consume (P1)

- [ ] T040 Create `routes/publish/` with the copyable publish snippet panel (copy-confirm).
- [ ] T041 Restyle `UploadForm.svelte` and add a **drag-and-drop** drop zone (file-picker fallback);
  created/updated/rejected feedback; block upload to non-hosted repos.
- [ ] T042 e2e/unit for copy snippet + drag-drop upload + read-only-repo prevention (US3).
  **Checkpoint: US3 demoable → Phase 1 shippable.**

- [ ] T049 Gate: `npm run check && npm run build && npm run build-storybook && bash frontend/scripts/e2e.sh`
  green; SC-002 (no backend change) and SC-003 (capability parity) confirmed.

---

## Phase 2 — Cheap read-only data (P2)

### Foundational (backend, blocks P2 stories)

- [ ] T100 Add `catalog/CatalogService.kt`: aggregate `group:artifact` rows via `ArtifactStorage.walk`,
  reuse existing version-ordering for `latestVersion`, bounded by pagination + short-TTL per-repo cache
  (D4). Unit test `CatalogServiceTest` (one entry/coordinate, latest, count, size).
- [ ] T101 [P] Wire `StatsController.kt` (`GET /api/stats`) to the existing storage-usage snapshot source
  (D3); integration test `StatsControllerTest` asserts figures vs. known stored state.
- [ ] T102 `CatalogController.kt` (`GET /api/catalog?q=&repo=&page=`) over `CatalogService`; READ-authz;
  integration test `CatalogControllerTest` (C1–C3 from the contract).
- [ ] T103 [P] Extend `frontend/src/lib/api.ts` with `getStats()` and `getCatalog()` types/calls.

### User Story 4 — Dashboard with live stats (P2)

- [ ] T110 `lib/components/dashboard/StatCards.svelte` + Dashboard route bound to `/api/stats`; graceful
  skeleton when unavailable; story + unit test (US4 / SC-004).

### User Story 5 — Cross-repo catalog & search (P2)

- [ ] T120 `lib/components/catalog/CatalogTable.svelte` as the Repositories default view (row → artifact
  detail).
- [ ] T121 [P] Topbar `SearchBox` filtering the catalog by coordinate (`q`).
- [ ] T122 Per-repo **view toggle** between catalog and the Phase-1 raw folder view.
- [ ] T123 e2e for catalog correctness + search + toggle (US5 / SC-005). **Checkpoint: Phase 2 shippable.**

- [ ] T129 Gate: `./gradlew :backend:build` + frontend gates green.

---

## Phase 3 — Stateful admin (P3, depends on persistence)

### User Story 6 — Persistence foundation (P3, blocks all other P3 work)

- [ ] T200 Add deps to `gradle/libs.versions.toml` **and pin in `gradle/verification-metadata.xml`** (D5,
  Principle IV): Spring Data JPA, Flyway (+ postgres module if needed), `sqlite-jdbc`, SQLite Hibernate
  dialect, `postgresql`.
- [ ] T201 `persistence/` config: single `DataSource` selected by `relikquary.persistence.backend =
  sqlite|postgres` (default sqlite, file under work dir); JPA + Flyway enabled; `application.yml` keys.
- [ ] T202 Flyway baseline migration under `backend/src/main/resources/db/migration/` creating
  `api_token`, `managed_user`, `publish_event`, `setting` (data-model.md), engine-portable.
- [ ] T203 `PersistenceConfigTest`: schema auto-init + round-trip persistence on **SQLite** (file/in-mem)
  and **Postgres via Testcontainers** (Principle II); admin path fails soft when DB down (FR-020).
  **Checkpoint: persistence ready.**

### User Story 7 — API tokens (P3)

- [ ] T210 JPA `ApiToken` entity + repository; secret generation + salted-hash storage; one-time reveal
  (data-model.md).
- [ ] T211 Spring Security token authentication provider layered onto existing auth (D6): resolve by hash,
  enforce scope→`Action`, update `lastUsedAt`, reject revoked/unknown.
- [ ] T212 `protocol/admin/TokenController.kt` (POST/GET/DELETE `/api/admin/tokens`), admin-authz.
- [ ] T213 `TokenAuthRoundTripIT`: real Maven + Gradle publish/resolve with a scoped token; revoke →
  rejected (T1–T3 / SC-007). `TokenControllerTest` for the API.
- [ ] T214 [P] Frontend `lib/components/admin/TokensTable.svelte` + token-create reveal flow; surface the
  token panel on the Publish screen. **Checkpoint: US7 demoable.**

### User Story 8 — User management (P3)

- [ ] T220 JPA `ManagedUser` entity + repository; compose a JPA `UserDetailsService` with the existing
  `InMemoryUserDetailsManager` so config users keep working (D7, FR-016).
- [ ] T221 `protocol/admin/UserController.kt` (GET/POST/PATCH/DELETE `/api/admin/users`), admin-authz.
- [ ] T222 `UserControllerTest` + an IT proving a managed user authenticates with its role and a config
  user is **not** locked out (U1–U3). 
- [ ] T223 [P] Frontend `lib/components/admin/UsersTable.svelte` + create/edit; the Users tab.
  **Checkpoint: US8 demoable.**

### User Story 9 — Publish history & attribution (P3)

- [ ] T230 JPA `PublishEvent` entity + repository; hook `RepositoryController.publish` to record **after**
  a successful store, best-effort (must not fail/alter the publish — D8, Principle IV).
- [ ] T231 `protocol/admin/ActivityController.kt` (`GET /api/activity`, newest first); artifact-detail
  attribution = latest event for the coordinate.
- [ ] T232 `PublishHistoryIT`: known-principal publish appears in activity + attribution; anonymous shows
  "unknown" (A1–A2 / SC-008).
- [ ] T233 [P] Frontend `dashboard/RecentPublishes.svelte` on the Dashboard + attribution on artifact
  detail. **Checkpoint: US9 demoable.**

### User Story 10 — Runtime settings (P3)

- [ ] T240 JPA `Setting` entity + repository; fixed allow-list of safe keys + per-key validation (D9).
- [ ] T241 `protocol/admin/SettingsController.kt` (GET/PUT `/api/admin/settings`), admin-authz; invalid →
  400 with prior value retained.
- [ ] T242 `SettingsControllerTest` (S1–S3 / SC-? ) — persistence across restart, validation, non-admin
  denied.
- [ ] T243 [P] Frontend `lib/components/admin/SettingsForm.svelte` + the Settings route.
  **Checkpoint: Phase 3 shippable.**

- [ ] T249 Gate: `./gradlew :backend:build` (detekt 0, Kover, **dependency verification** with the new
  pins) + frontend gates green.

---

## Final polish

- [ ] T300 [P] Accessibility/contrast pass on the dark theme (focus states, AA contrast for text on
  surfaces).
- [ ] T301 [P] Empty/loading/error states themed for every screen (no repos, no artifacts, no tokens, no
  users, no recent publishes).
- [ ] T302 [P] Large-listing behavior verified (catalog + folder view pagination/virtualization;
  truncation disclosed).
- [ ] T303 Update `README.md` (UI screenshots/sections) and document `relikquary.persistence.*` config.
- [ ] T304 SC verification sweep (SC-001…SC-009) against `quickstart.md`.

## Dependencies & parallelism

- **Phase order**: A → P1 → P2 → P3; each phase ships on its own.
- **P1**: T010/T011 block T020+; T021/T030–T033 are `[P]` (distinct components).
- **P2**: T100 blocks T102/T120–T123; T101/T103 are `[P]`.
- **P3**: T200→T203 (persistence) block **all** of US7–US10; within them, frontend `[P]` tasks follow
  their backend endpoint. US7/US8/US9/US10 are largely independent once persistence exists.
