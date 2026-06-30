# Quickstart: Frontend Redesign — "Artifact Sanctuary"

Per-phase validation. Each phase is independently demonstrable. Design details live in
[spec.md](./spec.md), [plan.md](./plan.md), [data-model.md](./data-model.md), and
[contracts/frontend-redesign.md](./contracts/frontend-redesign.md).

## Prerequisites

- Backend: `./gradlew :backend:bootRun` (or `:backend:test` for the suite).
- Frontend: `cd frontend && npm ci && npm run dev` (standalone), plus `npm run check`, `npm run build`,
  `npm run build-storybook`, and the e2e harness `bash frontend/scripts/e2e.sh` (real backend + Chromium).
- Some artifacts published to a hosted repo (e.g. via the existing Maven/Gradle round-trip harness) so the
  catalog and stats have content.

## Phase 1 — Reskin (no backend change)

**Automated**
```sh
cd frontend && npm run check && npm run build && npm run build-storybook
bash frontend/scripts/e2e.sh        # existing e2e, updated to the new markup
```
**Manual walkthrough**
- Load the UI unauthenticated → themed full-screen **login**; sign in → **sidebar shell** appears.
- Sidebar navigates Dashboard / Repositories / Publish / Users & Tokens / Settings; the active item and
  topbar title track the route; sign-out returns to login.
- Repositories → open a repo → **raw folder view**: breadcrumbs + folder/file listing; open a file →
  details panel (size, last-modified, checksums) + working **download**; **delete** when authorized.
- Open a coordinate's version dir → coordinate + Gradle Module badge + **Gradle/Maven snippets**.
- Publish → copyable snippet (copy confirms) + **drag-and-drop** upload to a hosted path with
  created/updated/rejected feedback; non-hosted upload is prevented.
- Users & Tokens / Settings render as themed **placeholders** (enabled in Phase 3).

Expected: full visual parity with the mockup; **every** pre-existing capability still works (SC-003), and
no backend endpoint changed (SC-002).

## Phase 2 — Cheap read-only data

**Automated**
```sh
./gradlew :backend:test --tests '*StatsControllerTest' --tests '*CatalogControllerTest' \
                        --tests '*CatalogServiceTest'
```
**Manual**
- Dashboard shows real **repository count**, **artifact count**, **storage used** matching actual state
  (SC-004); momentarily-unavailable stats degrade to skeletons, not a broken page.
- Repositories defaults to the **catalog**: one row per `group:artifact` with correct latest version +
  version count (SC-005); typing in **topbar search** filters; a row opens artifact detail.
- Toggle a repository between **catalog** and **raw folder** view and back.

Expected: stats/catalog derive from existing stored state; no change to how artifacts are stored/resolved
(FR-012).

## Phase 3 — Stateful admin (depends on persistence)

**Automated**
```sh
# default embedded store + external Postgres (Testcontainers), per Principle II
./gradlew :backend:test --tests '*PersistenceConfigTest' \
                        --tests '*TokenAuthRoundTripIT'   --tests '*TokenControllerTest' \
                        --tests '*UserControllerTest'     --tests '*PublishHistoryIT' \
                        --tests '*SettingsControllerTest'
```
**Manual**
- Start with no persistence config → embedded store; create data → restart → data persists (SC-006).
  Reconfigure to Postgres → same features work.
- **Token**: issue a `publish`-scoped token (secret shown **once**) → use it as client credentials to
  `mvn deploy`/`gradle publish` → succeeds → **revoke** → next use rejected (SC-007).
- **Users**: create a managed user with a role via the UI → sign in as them → role applies; existing
  config (YAML) users still sign in (no lockout).
- **History**: publish as a known principal → appears in **Recent Publishes** and as the artifact's
  **attribution** (SC-008); an anonymous publish shows "unknown".
- **Settings**: change an allow-listed setting → save → reload/restart → persists; invalid value →
  rejected, prior value kept; non-admin → read-only/denied.
- **Resilience**: stop the DB → admin screens report a clear error (503) while browse/stats/catalog stay
  up (FR-020).

## Gate (every phase)

```sh
./gradlew :backend:build      # compile + detekt(0) + Kover + dependency verification (Principle III/IV)
```
Phase 3 additionally requires every new dependency pinned in `gradle/verification-metadata.xml`, or this
build fails by design.
