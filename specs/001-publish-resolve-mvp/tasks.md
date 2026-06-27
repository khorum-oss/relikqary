---
description: "Task list for Core Publish-and-Resolve MVP"
---

# Tasks: Core Publish-and-Resolve MVP

**Input**: Design documents from `specs/001-publish-resolve-mvp/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/repository-http.md, quickstart.md

**Tests**: Included — the project constitution (Principle II) makes test-first + integration-verified
discipline non-negotiable, so test tasks are mandatory here, not optional.

**Organization**: Tasks are grouped by user story (US1 publish, US2 resolve, US3 storage redirect) so
each is independently implementable and testable.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: US1 / US2 / US3 (setup, foundational, and polish tasks carry no story label)
- All paths are repository-relative.

## Path Conventions

Single Spring Boot `backend` module (plan.md "Structure Decision"):
- Main: `backend/src/main/kotlin/org/khorum/oss/relikquary/{protocol,ingestion,storage,coordinate,config}/`
- Tests: `backend/src/test/kotlin/org/khorum/oss/relikquary/{unit,integration}/`
- Config: `backend/src/main/resources/application.yml`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Realize the `backend` module and bring the inherited Konstellation build in line with the
constitution (research.md §8). Order matters where the same build file is touched.

- [x] T001 Create the `backend` module source tree (`backend/src/main/kotlin/org/khorum/oss/relikquary/` with empty `protocol/ingestion/storage/coordinate/config` package dirs and `backend/src/test/kotlin/org/khorum/oss/relikquary/{unit,integration}/`) per plan.md
- [x] T002 Add Spring Boot 4.1.x, Spring Web, Kotlin (jackson) and test deps (Spring Boot Test, Gradle TestKit, maven-invoker, MockK) to `gradle/libs.versions.toml`
- [x] T003 Create `backend/build.gradle.kts` applying the Spring Boot + Kotlin plugins, JDK 21 toolchain, and the catalog dependencies; declare the Spring Boot main class
- [x] T004 Clean root `build.gradle.kts`: bump toolchain 17→21, remove the `khorum.*` plugin aliases / Micronaut usage, delete the `project(":dsl")` references in `koverMergedReport` and the `sonar` `jacoco.xmlReportPaths`, and point Sonar coverage at the `backend` module
- [x] T005 Remove inherited cruft from `gradle.properties` (drop `micronautVersion`, `ksp.useKSP2`) and from `settings.gradle.kts` confirm `includeModules("backend")` resolves to the new module
- [x] T006 [P] Add detekt config and Kover setup for the `backend` module (zero-violation gate, coverage verification) per Principle III
- [x] T007 Enable dependency verification (`org.gradle.dependency.verification=strict` in `gradle.properties`) and regenerate `gradle/verification-metadata.xml` to cover the new Spring/test dependencies
- [x] T008 [P] Create `backend/src/main/kotlin/org/khorum/oss/relikquary/RelikquaryApplication.kt` (Spring Boot entrypoint) and `backend/src/main/resources/application.yml` with default `relikquary.storage.*` and `relikquary.publish.*` keys

**Checkpoint**: `./gradlew :backend:compileKotlin` succeeds and the app boots with an empty controller.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Shared domain + infrastructure every user story depends on (coordinate/layout model,
storage abstraction, configuration, HTTP routing skeleton with clean 404).

**⚠️ CRITICAL**: No user story work begins until this phase is complete.

- [x] T009 [P] Implement the coordinate + Maven-layout model in `backend/src/main/kotlin/org/khorum/oss/relikquary/coordinate/` (parse a request path into group/artifact/version/filename, classify RELEASE vs SNAPSHOT, map coordinate↔layout path) per data-model.md
- [x] T010 [P] Define the `ArtifactStorage` interface (`exists/get/put` keyed by repo-layout path) in `backend/src/main/kotlin/org/khorum/oss/relikquary/storage/`
- [x] T011 Implement `FilesystemArtifactStorage` in `backend/src/main/kotlin/org/khorum/oss/relikquary/storage/` (streaming read/write, atomic temp-file-then-move, root from config) — depends on T010
- [x] T012 [P] Implement `@ConfigurationProperties` for storage location (`relikquary.storage.filesystem.root`) and republish policy (`relikquary.publish.release-policy`) in `backend/src/main/kotlin/org/khorum/oss/relikquary/config/`
- [x] T013 Implement the repository HTTP controller skeleton in `backend/src/main/kotlin/org/khorum/oss/relikquary/protocol/`: wildcard `**` path mapping, path→storage-key validation rejecting traversal/escape segments (`..`, absolute, empty) with `400` (FR-012), and a clean `404` for absent keys (FR-008) — depends on T009, T010
- [x] T014 [P] Unit tests for coordinate parsing/classification, `FilesystemArtifactStorage` (using `@TempDir`), and path-traversal rejection (FR-012/SC-008 — assert `../` and absolute escapes cannot read/write outside the root) in `backend/src/test/kotlin/org/khorum/oss/relikquary/unit/`

**Checkpoint**: Storage, config, and routing exist; GET of an unknown path returns 404. User stories can begin.

---

## Phase 3: User Story 1 - Publish a library artifact (Priority: P1) 🎯 MVP

**Goal**: Accept a real Gradle `maven-publish` upload and store every file byte-for-byte at its
Maven-layout path, honoring the republish policy.

**Independent Test**: Run a real Gradle `maven-publish` against the running app and assert the publish
succeeds and all uploaded files exist in the configured store, byte-identical (SC-001, FR-001/002/003).

### Tests for User Story 1 (write first; ensure they FAIL before implementation) ⚠️

- [x] T015 [P] [US1] Integration test in `backend/src/test/kotlin/org/khorum/oss/relikquary/integration/`: `@SpringBootTest(RANDOM_PORT)` with the store root injected via `@DynamicPropertySource` → `@TempDir`, drive a real Gradle `maven-publish` (Gradle TestKit) and assert BUILD SUCCESSFUL + stored files byte-for-byte equal to the published ones, AND assert `maven-metadata.xml` actually landed in storage (research.md §3 verification guard)
- [x] T016 [P] [US1] Unit test for `RepublishPolicy` in `backend/src/test/kotlin/org/khorum/oss/relikquary/unit/` (existing RELEASE → reject; existing SNAPSHOT → overwrite; policy override). Add an integration assertion (in the US1 or US2 round-trip test) that a SNAPSHOT re-publish replaces stored contents while a RELEASE re-publish returns 409 (SC-007)

### Implementation for User Story 1

- [x] T017 [US1] Implement `RepublishPolicy` in `backend/src/main/kotlin/org/khorum/oss/relikquary/ingestion/` (classify coordinate, apply configured release policy) — depends on T009, T012
- [x] T018 [US1] Implement `PUT` handling in the protocol controller (`backend/.../protocol/`): read the request body stream, consult `RepublishPolicy`, persist via `ArtifactStorage`, return 201/200, or 409 on release conflict leaving stored bytes unchanged (FR-010, contracts/repository-http.md) — depends on T013, T011, T017
- [x] T019 [US1] Ensure faithful storage: no transformation of bytes/sidecars on the write path; verify checksum/signature sidecars are stored as received (FR-003, FR-006, FR-009)

**Checkpoint**: A real Gradle publish lands byte-identical files in the store; release re-publish is rejected.

---

## Phase 4: User Story 2 - Resolve from Maven and Gradle (Priority: P1)

**Goal**: Serve stored files back at Maven-layout paths so unmodified Maven AND Gradle clients resolve
the artifact byte-for-byte, with checksums verifying.

**Independent Test**: With an artifact present in the store, run a real Maven resolve and a real Gradle
resolve and assert both download identical, checksum-verified files (SC-002, SC-003, SC-004, FR-011).

### Tests for User Story 2 (write first; ensure they FAIL before implementation) ⚠️

- [x] T020 [P] [US2] Integration test in `backend/src/test/kotlin/org/khorum/oss/relikquary/integration/`: pre-seed (or publish via US1) a coordinate, then resolve with a real Maven client (maven-invoker) and assert download + checksum verification (SC-002)
- [x] T021 [P] [US2] Integration test: resolve the same coordinate with a real Gradle consumer (Gradle TestKit) and assert resolution + that files equal what Maven received (SC-003)
- [x] T022 [US2] End-to-end round-trip test: real Gradle publish → real Maven resolve AND real Gradle resolve → byte-for-byte hash comparison of published vs resolved files (SC-004, FR-003) — the authoritative Principle II test

### Implementation for User Story 2

- [x] T023 [US2] Implement `GET`/`HEAD` serving in the protocol controller (`backend/.../protocol/`): stream stored bytes with correct content length, 404 when absent — depends on T013, T011
- [x] T024 [US2] Serve `maven-metadata.xml` (and its sidecars) as stored on publish so clients discover versions (FR-005, research.md §3) — depends on T023

**Checkpoint**: Full publish→resolve round-trip is green for both Maven and Gradle clients.

---

## Phase 5: User Story 3 - Redirect storage to a different location (Priority: P2)

**Goal**: Changing only the configured storage location persists new publishes there, with no code change.

**Independent Test**: Configure location A, publish, assert files in A; reconfigure to B, publish, assert
files in B and A untouched (SC-005, FR-007).

### Tests for User Story 3 (write first; ensure they FAIL before implementation) ⚠️

- [x] T025 [P] [US3] Integration test in `backend/src/test/kotlin/org/khorum/oss/relikquary/integration/`: using `@DynamicPropertySource`, bind the store root to location A, publish, assert files in A; in a second context bound to location B, publish, assert files in B and A unchanged (SC-005). Inject config dynamically — do not rely on a manual restart.

### Implementation for User Story 3

- [x] T026 [US3] Confirm the storage root is sourced exclusively from `@ConfigurationProperties` (no hardcoded paths anywhere in `protocol`/`ingestion`/`storage`); fix any leak — depends on T011, T012

**Checkpoint**: Storage location is fully config-driven and swappable.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Gate-clean the build, document, and run the quickstart.

- [x] T027 Run `./gradlew build` and resolve any detekt/Kover violations until the full gate is green (Principle III); annotate justified exemptions with `@ExcludeFromCoverage`
- [x] T028 [P] Update `README.md` with a short Relikquary description and a pointer to `specs/001-publish-resolve-mvp/quickstart.md`
- [x] T029 Execute the `quickstart.md` manual round-trip end-to-end against a locally running instance and confirm all SC checks
- [x] T030 [P] Record deferred follow-ups as a short note (server-side `maven-metadata.xml` synthesis, configurable strict checksum-validation mode FR-009a, S3/Spaces backend, auth) in the feature dir for future specs

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies; build files (T003→T004→T005, T007) are partly sequential.
- **Foundational (Phase 2)**: depends on Setup; BLOCKS all user stories.
- **US1 (Phase 3)** and **US2 (Phase 4)**: both P1, depend on Foundational. US2's round-trip test (T022) exercises US1's publish path, so US1 should land first or alongside; US2's serving code (T023/T024) is independent of US1's PUT code and can be built in parallel.
- **US3 (Phase 5)**: depends on Foundational; mostly validation of config-driven storage.
- **Polish (Phase 6)**: after the desired stories are complete.

### Within Each User Story

- Tests written first and FAIL before implementation (Principle II).
- Storage/coordinate (foundational) before protocol handlers; `RepublishPolicy` before PUT handling.

### Parallel Opportunities

- T006 and T008 in Setup ([P]).
- T009, T010, T012, T014 in Foundational ([P], different files).
- Test tasks within a story marked [P] run together; US1 and US2 serving code can progress in parallel once Foundational is done.

---

## Implementation Strategy

### MVP First

1. Phase 1 Setup → 2 Foundational (build green, app boots, 404 works).
2. Phase 3 US1 (publish) + Phase 4 US2 (resolve) — together these are the round-trip MVP; STOP and validate via T022.
3. Phase 5 US3 (storage redirect) for deployment flexibility.
4. Phase 6 polish + quickstart validation.

### Notes

- [P] = different files, no incomplete-task dependency.
- Commit after each task or logical group; keep commit messages free of self-attribution.
- The authoritative acceptance gate is T022 (real publish→resolve round-trip) plus a green `./gradlew build`.
