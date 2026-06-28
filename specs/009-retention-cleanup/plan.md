# Implementation Plan: Retention & Cleanup Policies

**Branch**: `claude/spec-009-retention-cleanup` | **Date**: 2026-06-28 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/009-retention-cleanup/spec.md`

## Summary

Add opt-in per-repository retention/eviction that reclaims storage: snapshot retention (keep the N newest
and/or non-expired builds per snapshot artifact, purge older; releases never touched) and proxy cache
eviction (bound the cache by cached-at age and/or a size budget; evicted artifacts re-fetch). A
`CleanupService` runs on a configurable schedule and via an authorized `POST /api/cleanup` endpoint with
a dry-run mode and a report. A new `ArtifactStorage.walk` provides recursive enumeration for both
backends. No new dependencies; faithful storage and release immutability are preserved.

## Technical Context

**Language/Version**: Kotlin 2.3.21 on JDK 21.

**Primary Dependencies**: Spring Boot 4.1.0 (Web, Scheduling via `spring-context`), Spring Security 7.1.0
(reuse the feature-007 authorization manager). **No new dependency** — `@EnableScheduling`, `Duration`,
`DataSize` are Spring/JDK built-ins.

**Storage**: Existing `ArtifactStorage` (filesystem / S3) gains a recursive `walk`; deletion reuses
`delete`. No bytes are altered.

**Testing**: JUnit 5 unit tests (selection logic) + `@SpringBootTest(RANDOM_PORT)` + `HttpClient`
integration (seed builds, run `POST /api/cleanup`, assert); `walk` exercised on filesystem and S3.

**Target Platform**: Linux server (Spring Boot fat jar). `frontend/` unaffected.

**Project Type**: Web service (`backend/` module).

**Performance Goals**: Cleanup enumerates storage once per run (recursive `walk`) and deletes only
selected files; selection is in-memory. No added work on the publish/resolve hot paths.

**Constraints**: Strict dependency verification stays enabled and **untouched** (no new deps); detekt
zero, Kover holds. Cleanup never alters retained bytes; releases never touched.

**Scale/Scope**: One backend module; ~6 new classes (`CleanupProperties`, `RetentionPolicy` config,
`CleanupService`, `CleanupScheduler`, `CleanupController`, report DTO), `ArtifactStorage.walk` on both
backends, a manager tweak for the endpoint, and config/docs.

## Constitution Check

*GATE: re-checked after Phase 1 design — PASS.*

- **I. Repository Contract & Client Compatibility** — PASS. Additive config (`retention`/`cleanup`) and a
  new management endpoint (`/api/cleanup`); the Maven wire layout, resolution, and publish acceptance are
  unchanged. No configuration contract is removed.
- **II. Test-First & Integration-Verified** — PASS. Real round-trips over the HTTP + storage boundary
  (seed builds → cleanup → assert resolution); both storage backends exercised for the new `walk`. No
  mocking the store.
- **III. Quality Gates** — PASS. detekt/Kover unchanged; no gate weakened. No new dependency → no
  verification-metadata edit.
- **IV. Supply-Chain Integrity & Faithful Storage** — PASS, reinforced. Cleanup deletes only whole
  policy-selected files and never rewrites retained bytes; release coordinates and `maven-metadata.xml`
  are never removed. Zero new dependencies keeps `gradle/verification-metadata.xml` untouched.

No violations → Complexity Tracking not required.

## Project Structure

### Documentation (this feature)

```text
specs/009-retention-cleanup/
├── plan.md, research.md, data-model.md, quickstart.md
├── contracts/cleanup-api.md
├── checklists/requirements.md
└── tasks.md   # Phase 2 (/speckit-tasks — not created here)
```

### Source Code (repository root)

```text
backend/src/main/kotlin/org/khorum/oss/relikquary/
├── config/
│   ├── CleanupProperties.kt           # NEW: relikquary.cleanup (enabled, interval)
│   └── RepositoryProperties.kt        # MODIFIED: add RetentionPolicy (snapshot/cache) on Repo
├── storage/
│   ├── ArtifactStorage.kt             # MODIFIED: add walk(prefix): List<StoredObject> + StoredObject
│   ├── FilesystemArtifactStorage.kt   # MODIFIED: walk via Files.walk
│   └── S3ArtifactStorage.kt           # MODIFIED: walk via paginated listObjectsV2
├── cleanup/
│   ├── CleanupService.kt              # NEW: run(dryRun) → CleanupReport; snapshot + cache selection
│   └── CleanupScheduler.kt            # NEW: @ConditionalOnProperty + @Scheduled runner
├── protocol/
│   ├── CleanupController.kt           # NEW: POST /api/cleanup?dryRun=
│   └── dto/CleanupReport.kt           # NEW: report DTOs
├── security/
│   └── RepositoryAuthorizationManager.kt  # MODIFIED: require PUBLISH for /api/cleanup
├── RelikquaryApplication.kt           # MODIFIED: @EnableScheduling + register CleanupProperties
└── resources/application.yml          # MODIFIED: document cleanup + retention

backend/src/test/kotlin/org/khorum/oss/relikquary/
├── unit/
│   ├── SnapshotRetentionSelectionTest.kt   # NEW: build grouping/selection (keepLast, maxAge, keep-newest)
│   ├── CacheEvictionSelectionTest.kt        # NEW: age + size-budget selection
│   └── FilesystemWalkTest.kt                # NEW: recursive walk (@TempDir)
└── integration/
    ├── SnapshotRetentionTest.kt       # NEW: seed builds → cleanup → older gone, newest kept, resolves
    ├── ProxyEvictionTest.kt           # NEW: cache via stub upstream → evict (age/size) → re-fetch
    ├── CleanupAuthTest.kt             # NEW: POST /api/cleanup auth matrix + dry-run leaves storage intact
    └── S3WalkTest.kt                  # NEW: walk over s3mock (FR-011 parity)
```

**Structure Decision**: A new `cleanup/` package holds the service + scheduler; selection logic is pure
and unit-tested, with the controller and scheduler as thin entry points. `ArtifactStorage.walk` is the
one storage addition (recursive enumeration both backends need). Authorization reuses the feature-007
manager. `frontend/` is untouched.

## Implementation phases (high level)

1. **Storage walk** — `ArtifactStorage.walk` + `StoredObject` on both backends; unit/S3 parity tests.
2. **Config & policy** — `CleanupProperties`; `RetentionPolicy` (snapshot/cache) on `Repo`; register
   properties; document in `application.yml`.
3. **Selection logic** — pure snapshot build grouping/selection (D3) and cache eviction selection (D4);
   unit tests.
4. **Service, scheduler, endpoint** — `CleanupService.run(dryRun)` + `CleanupReport`; `@EnableScheduling`
   + gated `CleanupScheduler`; `CleanupController` (`POST /api/cleanup`); manager rule for `/api/cleanup`.
5. **Integration & verify** — snapshot retention, proxy eviction (+ re-fetch), dry-run, auth matrix,
   release-untouched; `./gradlew build` green (verification-metadata untouched); README; commit & push.

## Complexity Tracking

No constitution violations; section intentionally empty.
