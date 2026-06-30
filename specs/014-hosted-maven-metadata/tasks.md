---
description: "Task list for Server-Side maven-metadata.xml for Hosted Repositories"
---

# Tasks: Server-Side maven-metadata.xml for Hosted Repositories

**Input**: Design documents from `specs/014-hosted-maven-metadata/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/metadata.md, quickstart.md

**Tests**: Included — the spec mandates real Maven + Gradle verification (FR-012) and the correctness is
test-defined (multi-publisher listing, snapshot selection, checksums).

**Organization**: By user story (US1 artifact-level listing P1, US2 version-level snapshot P2, US3
per-type/proxy-untouched P3). New `metadata/` package; two thin hooks in `RepositoryController`; reuse of
`ArtifactStorage.list/walk`, `RepositoryPath`, and the cleanup snapshot `YYYYMMDD.HHMMSS-N` token. No new dep.

## Path Conventions

Backend module. Production under `backend/src/main/kotlin/org/khorum/oss/relikquary/`, tests under
`backend/src/test/kotlin/org/khorum/oss/relikquary/`. Reuse: `storage/ArtifactStorage.kt` (`list`, `walk`,
`write`), `coordinate/RepositoryPath.kt` (`version`, `artifactId`, `classify`), `cleanup/CleanupService.kt`
(`BUILD_TOKEN` snapshot format), `protocol/RepositoryController.kt` (publish ACCEPT branch + GET resolve).

---

## Phase 1: Setup

- [X] T001 Create the `backend/src/main/kotlin/org/khorum/oss/relikquary/metadata/` package and the matching test package `backend/src/test/kotlin/org/khorum/oss/relikquary/metadata/`.

---

## Phase 2: Foundational (blocking prerequisites for all stories)

- [X] T002 Add `metadata/MavenMetadata.kt` — data classes: `ArtifactMetadata(groupId, artifactId, versions, latest?, release?, lastUpdated)` and `SnapshotMetadata(groupId, artifactId, version, timestamp, buildNumber, lastUpdated, snapshotVersions)` with `SnapshotVersion(classifier?, extension, value, updated)`. Pure data, no I/O.
- [X] T003 Add `metadata/MavenMetadataXml.kt` — a JDK StAX (`javax.xml.stream`) serializer producing deterministic, byte-faithful `maven-metadata.xml` bytes for both `ArtifactMetadata` and `SnapshotMetadata` (stable element order, UTF-8), per data-model.md element shapes.
- [X] T004 Add a shared snapshot build-token helper (e.g. `metadata/SnapshotBuild.kt` or extend an existing coordinate util) that parses the canonical `YYYYMMDD.HHMMSS-N` token and `{artifactId}-{base}-{timestamp}-{N}[-{classifier}].{ext}` filenames; reconcile with `cleanup/CleanupService.kt` `BUILD_TOKEN` so there is a single definition of the format (refactor cleanup to use it if cheap, else mirror with a shared const).
- [X] T005 Add `metadata/HostedMetadataService.kt` (`@Service`) skeleton: derive `artifactDir`/`groupId`/`artifactId` from a published `RepositoryPath`; enumerate versions via `storage.list(artifactDir)` and file mtimes via `storage.walk`; a checksum helper writing `maven-metadata.xml` + `.sha1` + `.md5` (via `MessageDigest`) atomically through `storage.write`; and an in-process per-coordinate striped lock. Method stubs `regenerate(repo, path)` and `ensureForRead(repo, path)`.

**Checkpoint**: The metadata model, serializer, snapshot-token parsing, enumeration, checksum-write, and
per-coordinate locking exist — stories can wire generation and triggers.

---

## Phase 3: User Story 1 — Reliable version listing across independent publishers (Priority: P1) 🎯 MVP

**Goal**: After publishes from independent clients, the served artifact-level metadata lists every stored
version with correct latest/release/lastUpdated and consistent checksums.

**Independent Test**: Two publishers PUT only their own version + their own partial metadata; GET the
artifact metadata → both versions present, correct markers, checksums match.

- [X] T006 [US1] Implement artifact-level build in `HostedMetadataService.regenerate`: enumerate version dirs, compute `versions` (deploy order by mtime), `latest` (newest), `release` (newest non-`-SNAPSHOT`, omitted if none), `lastUpdated` (max mtime, UTC `yyyyMMddHHmmss`); serialize via `MavenMetadataXml` and write metadata + `.sha1` + `.md5` under the per-coordinate lock. (FR-001, FR-002, FR-003)
- [X] T007 [US1] Wire the publish hook in `protocol/RepositoryController.kt`: in the `PublishDecision.ACCEPT` branch, after `storage.write`, when `target.repo.kind == RepositoryKind.HOSTED` and `target.path.classify() != PathKind.METADATA`, call `metadataService.regenerate(repo, path)`. Keep the response/metrics behavior unchanged. (FR-001, FR-010)
- [X] T008 [US1] Wire compute-on-read in `RepositoryController` resolve, gated on `target.repo.kind == RepositoryKind.HOSTED`: when a GET targets a hosted repo's `maven-metadata.xml` and the stored object is **absent (missing)**, call `metadataService.ensureForRead(repo, path)` to compute, write-through, and serve; otherwise serve the stored file as today. (Regeneration runs on every publish, so the stored copy is effectively never stale — the fallback covers the missing case.) Do NOT touch proxy/group resolution. (FR-001 hybrid fallback, FR-006)
- [X] T009 [P] [US1] Add `metadata/MavenMetadataXmlTest.kt` — assert artifact-level XML shape: groupId/artifactId, `<versions>` contents, `<latest>`/`<release>` presence/omission, `<lastUpdated>` format. (FR-002, FR-009)
- [X] T010 [P] [US1] Add `metadata/HostedMetadataServiceTest.kt` — unit: multi-version enumeration produces the full version list and correct latest/release/lastUpdated from stored mtimes (mock/in-memory storage). (FR-001, FR-002)
- [X] T011 [US1] Add `integration/HostedMetadataHttpTest.kt` — `@SpringBootTest(RANDOM_PORT)`: publisher A PUTs widget 1.0.0 (+ metadata listing only 1.0.0), publisher B PUTs widget 2.0.0 (+ metadata listing only 2.0.0); GET the artifact `maven-metadata.xml` → lists BOTH versions, `release`/`latest` = 2.0.0; GET `.sha1`/`.md5` → match the served bytes. (FR-001, FR-003, FR-007, SC-001, SC-002, SC-004)

**Checkpoint**: MVP — independent publishers no longer clobber the listing; served metadata is authoritative.

---

## Phase 4: User Story 2 — Correct snapshot resolution (Priority: P2)

**Goal**: Version-level snapshot metadata reflects the newest stored build; snapshot versions appear in the
artifact listing.

**Independent Test**: Publish multiple timestamped builds of a `-SNAPSHOT`; GET the version-level metadata →
newest build; artifact listing includes the snapshot.

- [X] T012 [US2] Implement snapshot build enumeration in `HostedMetadataService`: for a `-SNAPSHOT` version dir, `walk` the dir, parse build tokens (T004 helper), select the newest by (timestamp, buildNumber), and collect its (classifier, extension) entries. (FR-004)
- [X] T013 [US2] Build + write version-level snapshot `maven-metadata.xml` (+ checksums) and trigger it from `regenerate` whenever the published version is `-SNAPSHOT` (in addition to the artifact-level regeneration). (FR-004, FR-003)
- [X] T014 [P] [US2] Extend `MavenMetadataXmlTest`/`HostedMetadataServiceTest` — version-level snapshot XML shape (`<snapshot>` timestamp/buildNumber, `<snapshotVersions>`) and newest-build selection across multiple builds. (FR-004, FR-009)
- [X] T015 [US2] Add an integration assertion (in `HostedMetadataHttpTest` or a sibling): publish two timestamped builds of a `-SNAPSHOT` (optionally as two publishers); GET version-level metadata → newest build; artifact-level listing includes the snapshot version. (FR-004, SC-003)

**Checkpoint**: Snapshot resolution is server-authoritative across builds/publishers.

---

## Phase 5: User Story 3 — Mixed and per-type repositories behave correctly (Priority: P3)

**Goal**: Release/snapshot/mixed hosted repos each produce correct metadata; proxy/group untouched.

**Independent Test**: Mixed repo lists releases + snapshots with correct markers; proxy metadata still from
upstream.

- [X] T016 [US3] Add tests confirming per-type correctness and isolation: mixed hosted repo lists both a release and a snapshot with `release`=highest release and `latest`=newest overall; and a proxy repo's `maven-metadata.xml` is still served from upstream (existing proxy/group tests unaffected — verify the read hook only fires for HOSTED). Add a guard assertion if any gap is found. (FR-005, FR-006, SC-005)

**Checkpoint**: Correct across all repository types; proxy/group behavior preserved.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T017 Add `integration/HostedMetadataRoundTripTest.kt` — real Maven + Gradle clients (external-process harness, mirror `PublishResolveRoundTripTest`) resolve a version range / `latest` and a `-SNAPSHOT` against multi-version, multi-publisher hosted state; assert correct resolution. (FR-012, SC-003)
- [X] T018 Add a convergence test: concurrent/rapid sequential publishes of the same coordinate result in a served listing containing every published version (no dropped/half-written metadata). (FR-007)
  - Note: the deletion/retention edge (a removed version should stop being advertised) is covered transitively — the next publish regenerates from a full scan and the read-side fallback recomputes when the file is absent (per data-model.md); no dedicated cleanup-integration task is added in this feature.
- [X] T019 [P] Document in `README.md` (and `application.yml` comment if relevant) that hosted `maven-metadata.xml` is now server-authoritative (generated/merged from stored versions), while proxy metadata remains pass-through. (FR-010)
- [X] T020 Run `./gradlew :backend:build` and confirm green: new metadata unit/HTTP/round-trip tests pass, detekt zero, Kover holds, strict dependency verification unchanged (no new deps), and all pre-existing publish/resolve/auth/proxy/group/cleanup tests still pass; confirm no non-metadata artifact is altered. (FR-008, FR-010, SC-006)

---

## Dependencies & Execution Order

- **Setup (T001)** → **Foundational (T002–T005)** precede all stories.
- **US1 (T006–T011)** depends on Foundational → MVP; deliver first.
- **US2 (T012–T015)** depends on Foundational + the artifact-level regenerate trigger (T006/T007).
- **US3 (T016)** depends on US1/US2 behavior existing.
- **Polish (T017–T020)** after the stories; T020 is the final gate.

## Parallel Opportunities

- Foundational: T002, T003, T004 are largely independent files (T003 depends on T002's types).
- US1: T009 and T010 (`[P]`, different test files) parallel; T011 after T006–T008.
- US2: T014 (`[P]`) parallel with T012/T013 implementation once types exist.
- T019 (docs) is `[P]`.

## Implementation Strategy

**MVP = Phase 1 + Phase 2 + Phase 3 (US1)**: the core defect (independent publishers clobbering the listing)
is fixed and proven by the two-publisher HTTP test. US2 adds full snapshot fidelity; US3 confirms per-type
correctness and proxy isolation; Polish adds the real-client round-trip, the convergence test, docs, and the
full-build gate.
