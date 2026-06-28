---
description: "Task list for retention & cleanup policies"
---

# Tasks: Retention & Cleanup Policies

**Input**: `specs/009-retention-cleanup/`. **Tests**: included (Constitution Principle II — real round
trips). Backend-only; all paths under `backend/src/.../org/khorum/oss/relikquary/`. No new dependencies.

## Phase 1: Setup

- [X] T001 Confirm no new dependencies (Spring scheduling is in `spring-context`); add `@EnableScheduling`
  to `RelikquaryApplication.kt` and create the `cleanup/` package directory.

## Phase 2: Foundational (blocking prerequisites for all stories)

- [X] T002 Add `walk(prefix): List<StoredObject>` (recursive, files only, with key/size/lastModified) and
  the `StoredObject` type to `storage/ArtifactStorage.kt`.
- [X] T003 Implement `walk` in `storage/FilesystemArtifactStorage.kt` (`Files.walk`, traversal-safe) and
  in `storage/S3ArtifactStorage.kt` (paginated `listObjectsV2`, no delimiter).
- [X] T004 [P] Unit test `unit/FilesystemWalkTest.kt` (`@TempDir`): nested files enumerated with sizes +
  last-modified; empty prefix returns nothing.
- [X] T005 [P] Integration test `integration/S3WalkTest.kt` (extend the s3mock harness): `walk` over S3
  returns the same recursive set (FR-011 parity).
- [X] T006 Add `config/CleanupProperties.kt` (`@ConfigurationProperties("relikquary.cleanup")`:
  `enabled=false`, `interval=PT1H`); register it in `RelikquaryApplication.kt`
  `@EnableConfigurationProperties`.
- [X] T007 Extend `config/RepositoryProperties.kt`: add `retention: RetentionPolicy?` on `Repo` with
  `RetentionPolicy(snapshot: SnapshotRetention?, cache: CacheEviction?)`,
  `SnapshotRetention(keepLast: Int?, maxAge: Duration?)`, `CacheEviction(maxAge: Duration?, maxSize:
  DataSize?)` (all optional; absent ⇒ no cleanup).

**Checkpoint**: storage can enumerate recursively on both backends; cleanup config binds.

## Phase 3: User Story 1 — Snapshot retention (Priority: P1)

**Goal**: Keep the newest builds of each snapshot artifact, purge older; releases and metadata untouched.

**Independent test**: Seed 5 timestamped builds of one snapshot artifact with keep-last-3, run cleanup,
confirm the 3 newest remain, the 2 oldest are gone, and the artifact still resolves.

- [X] T008 [US1] Add snapshot selection to `cleanup/CleanupService.kt` (pure helper): group files in a
  `-SNAPSHOT` version dir by the Maven `…-yyyyMMdd.HHmmss-N.ext` build token, order by timestamp, select
  builds beyond `keepLast` (oldest first) and/or older than `maxAge`, always keep the newest; never select
  `maven-metadata.xml` or non-timestamped files.
- [X] T009 [P] [US1] Unit test `unit/SnapshotRetentionSelectionTest.kt`: keepLast trims oldest; maxAge
  trims expired; keep-newest enforced; metadata/non-timestamped never selected.
- [X] T010 [US1] Integration test `integration/SnapshotRetentionTest.kt` (`@SpringBootTest`): PUT 5
  timestamped builds to a snapshot repo (keepLast=3), invoke `CleanupService.run(dryRun=false)` directly
  (the endpoint is built in US3), assert the 2 oldest builds' files are gone, the 3 newest remain,
  `maven-metadata.xml` remains, and a GET of a retained build resolves; a release repo with content is
  unchanged (also covers the group/unconfigured no-op selection).

**Checkpoint**: snapshot repositories stay bounded; releases/metadata safe.

## Phase 4: User Story 2 — Proxy cache eviction (Priority: P1)

**Goal**: Bound the proxy cache by cached-at age and/or size budget; evicted artifacts re-fetch.

**Independent test**: Cache several artifacts, run cleanup with a small budget (and/or maxAge), confirm
the selected cached files are removed, then request an evicted artifact and confirm it re-fetches.

- [X] T011 [US2] Add cache-eviction selection to `cleanup/CleanupService.kt` (pure helper): from the
  proxy's walked files, select those older than `maxAge`, then (for `maxSize`) sort remaining oldest-first
  and select until the total is within budget.
- [X] T012 [P] [US2] Unit test `unit/CacheEvictionSelectionTest.kt`: age threshold selects older files;
  size budget evicts oldest-first until within budget; both combined.
- [X] T013 [US2] Integration test `integration/ProxyEvictionTest.kt` (stub upstream from feature 006):
  resolve several artifacts to populate the cache, run cleanup with a tiny `maxSize` (and a `maxAge`),
  assert the selected cached files are evicted, then GET an evicted artifact and assert it is re-fetched
  and served (FR-005).

**Checkpoint**: proxy caches stay within budget with safe re-fetch.

## Phase 5: User Story 3 — Schedule, on-demand, dry-run, report (Priority: P2)

**Goal**: Cleanup runs on a schedule and via an authorized endpoint with dry-run and a report.

**Independent test**: A dry-run reports a selection while storage is unchanged; a real run's report
matches what was removed; the endpoint requires authorization.

- [X] T014 [US3] Implement `cleanup/CleanupService.kt` `run(dryRun): CleanupReport` orchestration: iterate
  configured repos, apply snapshot selection (hosted snapshot/mixed) or cache selection (proxy), delete
  selected files unless `dryRun`, and aggregate per-repo + total items/bytes; add report DTOs in
  `protocol/dto/CleanupReport.kt`.
- [X] T015 [US3] Add `cleanup/CleanupScheduler.kt` (`@ConditionalOnProperty("relikquary.cleanup.enabled")`,
  `@Scheduled(fixedDelayString="${relikquary.cleanup.interval:PT1H}")` — `fixedDelayString` accepts an
  ISO-8601 `Duration` string, so it binds the `interval` property directly) calling `run(dryRun=false)`.
- [X] T016 [US3] Add `protocol/CleanupController.kt` `POST /api/cleanup?dryRun={bool}` returning the
  `CleanupReport`; default `dryRun=false`.
- [X] T017 [US3] Extend `security/RepositoryAuthorizationManager.kt` with a non-repo-scoped branch: before
  the repo-path parsing, if the request path is `/api/cleanup`, return
  `AuthorizationDecision(<auth holds ROLE_PUBLISH>)` (reuse a small `RepositoryAuthorizer` helper rather
  than `permits(repo, …)`, which needs a repo). All other non-repo `/api` paths keep returning grant.
  Open when security is disabled (manager not wired).
- [X] T018 [P] [US3] Integration test `integration/CleanupAuthTest.kt`: `POST /api/cleanup` unauthenticated
  → 401, authenticated non-publisher → 403, publisher → 200; a `dryRun=true` run reports a non-empty
  selection but leaves storage byte-for-byte unchanged; security disabled ⇒ open.

**Checkpoint**: operators can schedule, preview (dry-run), and trigger cleanup with reporting + auth.

## Phase 6: Polish & verify

- [X] T019 [P] Document `relikquary.cleanup` and the per-repo `retention` block in
  `backend/src/main/resources/application.yml` and add a Retention & Cleanup section to `README.md`
  (snapshot keep-N/max-age, proxy max-age/max-size, schedule, `POST /api/cleanup` + dry-run, safety).
- [X] T020 `./gradlew build` green (detekt zero + Kover + all unit/integration incl. retention/eviction/
  dry-run/auth and the S3 walk parity; `verification-metadata.xml` untouched); commit & push to
  `claude/spec-009-retention-cleanup`.

## Dependencies

Setup (T001) + Foundational (T002–T007) block the stories. US1 (T008–T010) and US2 (T011–T013) both build
on `walk` + config and the selection helpers; they are independent of each other. US3 (T014–T018) wraps
the selection logic in the service/scheduler/endpoint (T014 depends on T008 + T011) and adds the auth
rule. Polish (T019–T020) last.

## Parallel opportunities

- T004 ∥ T005 (walk tests, different files). T009 ∥ T012 (selection unit tests). T018 ∥ T019.
- US1 and US2 selection helpers (T008, T011) touch the same file `CleanupService.kt` — author sequentially,
  but their unit tests (T009, T012) are parallel.

## MVP scope

Setup + Foundational + **US1** (snapshot retention) is the MVP — it reclaims the primary source of
unbounded growth. **US2** (proxy eviction) is the equally-P1 companion; **US3** (schedule/endpoint/dry-run)
makes it operable.
