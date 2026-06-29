---
description: "Task list for First-Class Gradle Plugin Portal Support"
---

# Tasks: First-Class Gradle Plugin Portal Support

**Input**: Design documents from `specs/012-gradle-plugin-portal/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/plugin-portal.md, quickstart.md

**Tests**: Included — the spec mandates a real Gradle round-trip (FR-011) and config/behavior assertions
(SC-001…SC-006). Verification is the bulk of this feature's work.

**Organization**: By user story (US1 P1, US2 P2, US3 P3). This feature adds no production Kotlin classes;
the single production change is `backend/src/main/resources/application.yml`, reusing feature 006's
proxy/group implementation.

## Path Conventions

Web-service layout: production config under `backend/src/main/resources/`, tests under
`backend/src/test/kotlin/org/khorum/oss/relikquary/`. Reference implementations:
`integration/ProxyCentralIT.kt` (guarded real-upstream), `integration/ProxyRoundTripTest.kt` &
`integration/GradleModuleProxyRoundTripTest.kt` (real-Gradle harness), `integration/GroupResolveTest.kt`
(group first-match), `integration/ProxyResolveTest.kt` (404 vs 502, 405, cache hit/miss with stub upstream).

---

## Phase 1: Setup

- [X] T001 Confirm branch `claude/spec-012-gradle-plugin-portal` is checked out from a synced `main` (011 merged), and `./gradlew :backend:build` is green before changes.

---

## Phase 2: Foundational (blocking prerequisite for all stories)

- [X] T002 Add the default `gradle-plugins` proxy repository to `relikquary.repositories` in `backend/src/main/resources/application.yml`, with `kind: proxy` and `remoteUrl: ${RELIKQUARY_GRADLE_PLUGIN_PORTAL_URL:https://plugins.gradle.org/m2/}`, plus an explanatory comment consistent with the existing `maven-central` entry.
- [X] T003 Append `gradle-plugins` as the last member of the default `public` group in the same file so `members: [releases, maven-central, gradle-plugins]`.

**Checkpoint**: Default config now ships the portal proxy inside the aggregate group. Existing
`RepositoryRegistry` validation already accepts a proxy-in-group (it accepts `maven-central`), so no code
change is required; `./gradlew :backend:bootJar` should start cleanly.

---

## Phase 3: User Story 1 — Resolve a Gradle plugin through Relikquary out of the box (Priority: P1) 🎯 MVP

**Goal**: A real Gradle build pointed only at Relikquary's default `public` group resolves and applies a
published plugin (marker POM + implementation), which Relikquary fetches and caches.

**Independent Test**: Apply a real plugin through `…/public` against a default-config server and confirm
success; confirm the artifacts are cached under `gradle-plugins/…`.

- [X] T004 [US1] Add `integration/DefaultRepositoriesTest.kt` — `@SpringBootTest` with default config; assert the `RepositoryRegistry`/`RepositoryProperties` expose a `gradle-plugins` proxy whose `remoteUrl` resolves to `https://plugins.gradle.org/m2/`, and that the `public` group's members are exactly `[releases, maven-central, gradle-plugins]` in order. Offline-safe (no network), always runs. (FR-001, FR-002, SC-001 config contract)
- [X] T005 [US1] Add `integration/GradlePluginPortalRoundTripIT.kt` — boot the app with default config; using the existing real-Gradle external-process harness (mirror `GradleModuleProxyRoundTripTest`/`ProxyRoundTripTest`), generate a throwaway Gradle project whose `settings.gradle.kts` points `pluginManagement.repositories` (and `dependencyResolutionManagement.repositories`) at the test server's `…/public`, applying the pinned plugin `org.jetbrains.gradle.plugin.idea-ext` version `1.1.8` via `plugins { }` (chosen for a small, dependency-light, stable footprint that applies on any JVM project; implement may swap for a smaller reliably-available plugin if needed, but the version MUST be pinned for a deterministic round-trip). Assert the build configures successfully. Guard with an upstream-reachability probe and `assumeTrue(...)` so it auto-skips offline (mirror `ProxyCentralIT`). (FR-003, FR-005, FR-011, SC-001, SC-003, US1)
- [X] T006 [US1] In `GradlePluginPortalRoundTripIT`, after a successful resolve assert the plugin marker POM (and the implementation artifact) are now present in the configured storage root under the `gradle-plugins/…` cache key (byte-non-empty), proving the fetch was cached. (FR-003, FR-004)

**Checkpoint**: US1 delivers the MVP — plugins resolve out of the box. Independently demoable.

---

## Phase 4: User Story 2 — Continue building when the Plugin Portal is unreachable (Priority: P2)

**Goal**: Cached plugin artifacts resolve with the upstream down; uncached artifacts during an outage report
`502`, distinct from `404` for nonexistent coordinates.

**Independent Test**: Resolve once (cache), make upstream unreachable, re-resolve from cache; confirm 502 vs
404 distinction.

- [X] T007 [US2] Add an offline-safe behavior test for the `gradle-plugins` proxy in `integration/` (reuse the stub-upstream pattern from `ProxyResolveTest`, pointing `gradle-plugins.remoteUrl` at a local `com.sun.net.httpserver.HttpServer`): cache-miss fetch → store → serve; second request served from cache without contacting upstream (assert by stopping/asserting-no-hit on the stub). (FR-004, SC-002, US2#1)
- [X] T008 [US2] In the same test, assert status mapping for the plugin proxy: a coordinate the stub returns 404 for → `404` and nothing cached; with the stub stopped/unreachable and the coordinate uncached → `502` (upstream error). (FR-008, SC-006, US2#2)
- [X] T008b [US2] In the same stub-upstream test, assert metadata pass-through for the `gradle-plugins` proxy: a `maven-metadata.xml` request is served fresh from the stub on every call and is **never** written to the cache (verify no cache key is created for it, and a changed stub response is reflected on the next request). (FR-006)

**Checkpoint**: Offline resilience and error-distinction verified for the plugin proxy specifically
(general proxy behavior already covered by 006 tests).

---

## Phase 5: User Story 3 — Operator points the Plugin Portal proxy elsewhere (Priority: P3)

**Goal**: The portal upstream URL is overridable via `RELIKQUARY_GRADLE_PLUGIN_PORTAL_URL`, defaulting to
the portal when unset.

**Independent Test**: Override the env var → requests go to the override; unset → default applies.

- [X] T009 [US3] Add a test (in `integration/DefaultRepositoriesTest.kt` or a focused test) using `@SpringBootTest` properties / `@DynamicPropertySource` to set `RELIKQUARY_GRADLE_PLUGIN_PORTAL_URL` (or the resolved `relikquary.repositories[...].remoteUrl`) to a sentinel URL and assert the `gradle-plugins` proxy's effective `remoteUrl` reflects the override; and that the default applies when unset. Offline-safe. (FR-007, SC-004, US3)

**Checkpoint**: Configurability verified without code changes.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T010 [P] Document the plugin-portal default in `backend/src/main/resources/application.yml` comments and in `README.md`: the shipped `gradle-plugins` proxy, the `RELIKQUARY_GRADLE_PLUGIN_PORTAL_URL` override, and the client `settings.gradle.kts` `pluginManagement`/`dependencyResolutionManagement` snippet pointing at `…/public` (from contracts/plugin-portal.md §4). (FR-012)
- [X] T011 [P] Cross-check `specs/012-gradle-plugin-portal/quickstart.md` scenarios A–E against the implemented tests; adjust the doc if test names/paths differ.
- [X] T012 Run `./gradlew :backend:build` and confirm green: detekt zero violations, Kover thresholds hold, strict dependency verification unchanged (no new deps → `gradle/verification-metadata.xml` untouched), and all pre-existing proxy/group/publish/resolve/auth tests still pass. (FR-010, SC-005)
- [X] T013 Confirm `405` read-only enforcement covers the new repos: the existing proxy/group 405 tests are repository-name-agnostic, so `…/gradle-plugins/…` and `…/public/…` are already covered — verify this holds (a `PUT` returns `405` with `Allow: GET, HEAD`); only add an explicit assertion if a gap is found. (FR-009)

---

## Dependencies & Execution Order

- **Phase 1 (T001)** → **Phase 2 (T002–T003)** must precede all story phases (config is the foundation).
- **US1 (Phase 3)** depends only on Phase 2 → this is the MVP; deliver first.
- **US2 (Phase 4)** and **US3 (Phase 5)** depend on Phase 2; independent of US1 and of each other.
- **Phase 6 polish** after the story phases it documents/verifies.

## Parallel Opportunities

- T002 and T003 edit the same file (`application.yml`) → sequential.
- Across stories, the test files are distinct: T004/T005/T006 (US1), T007/T008 (US2), T009 (US3) can be
  written in parallel once Phase 2 lands — except T004 and T009 may share `DefaultRepositoriesTest.kt`
  (sequential if so).
- T010 and T011 (docs) are `[P]` — different files.

## Implementation Strategy

**MVP = Phase 1 + Phase 2 + Phase 3 (US1)**: ships the default plugin-portal proxy in the `public` group and
proves a real Gradle build resolves a plugin through it. US2 (offline/error-distinction) and US3
(configurable upstream) are additive verification layers. Polish closes docs + full-build green.
