---
description: "Task list for Streaming Proxy Cache (Tee)"
---

# Tasks: Streaming Proxy Cache (Tee)

**Input**: Design documents from `specs/015-proxy-streaming-cache/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/proxy-streaming-cache.md

**Tests**: Included and required — constitution Principle II mandates test-first (Red → Green) and a
real client/storage round-trip for any change to repository serving or a storage backend.

**Organization**: Shared mechanism in Foundational; each user story carries its distinctive tests and
any story-specific wiring so it stays independently verifiable.

## Path Conventions

Single affected module: `backend`. Main: `backend/src/main/kotlin/org/khorum/oss/relikquary/`.
Tests: `backend/src/test/kotlin/org/khorum/oss/relikquary/`.

---

## Phase 1: Setup

- [X] T001 Confirm scope adds no new dependency (so `gradle/libs.versions.toml` and `gradle/verification-metadata.xml` are untouched — constitution IV) and that all touchpoint files exist: `backend/src/main/kotlin/org/khorum/oss/relikquary/storage/ArtifactStorage.kt`, `storage/FilesystemArtifactStorage.kt`, `storage/S3ArtifactStorage.kt`, `repository/RepositoryResolver.kt`, `protocol/RepositoryController.kt`, and test helper `backend/src/test/kotlin/org/khorum/oss/relikquary/integration/StubUpstream.kt`.

---

## Phase 2: Foundational (blocking prerequisites for all stories)

**Purpose**: the backend-agnostic pending-write abstraction, the tee stream, and the failure-capable
test upstream that every user story depends on.

- [X] T002 [P] Add `ArtifactWrite` handle type in `backend/src/main/kotlin/org/khorum/oss/relikquary/storage/ArtifactWrite.kt`: `Closeable` with `val sink: OutputStream`, `fun commit(): Long`, `fun abort()`; `close()` aborts if neither committed nor aborted (never leaks a temp artifact). Document the isolation/atomic-promotion/byte-fidelity invariants from contracts/proxy-streaming-cache.md §1.
- [X] T003 Add `fun openWrite(key: String): ArtifactWrite` to the `ArtifactStorage` interface in `backend/src/main/kotlin/org/khorum/oss/relikquary/storage/ArtifactStorage.kt`, and allow an unknown size on `StoredArtifact` (nullable/sentinel `sizeBytes`) with a doc note that proxy-miss streams may have an unknown length while cache reads always know it.
- [X] T004 [P] Implement `openWrite` in `backend/src/main/kotlin/org/khorum/oss/relikquary/storage/FilesystemArtifactStorage.kt` using the existing temp-file-in-destination-dir pattern: `sink` writes to the temp file; `commit()` = `Files.move(..., ATOMIC_MOVE)` returning bytes; `abort()` = delete temp.
- [X] T005 [P] Implement `openWrite` in `backend/src/main/kotlin/org/khorum/oss/relikquary/storage/S3ArtifactStorage.kt`: `sink` writes to a temp file; `commit()` = `putObject(key, fromFile(temp))` returning bytes; `abort()` = delete temp (mirrors the existing `write` buffer-to-temp approach).
- [X] T006 [P] Write failing unit test `TeeInputStreamTest` in `backend/src/test/kotlin/org/khorum/oss/relikquary/unit/TeeInputStreamTest.kt`: reaching EOF ⇒ `commit()` called once and mirrored bytes equal source; close-before-EOF ⇒ `abort()` and no commit; a read `IOException` ⇒ `abort()`; double-close is safe (uses a fake `ArtifactWrite` recording commit/abort).
- [X] T007 Implement `TeeInputStream` in `backend/src/main/kotlin/org/khorum/oss/relikquary/proxy/TeeInputStream.kt` to pass T006: mirror each `read(...)` into `ArtifactWrite.sink`; track `eofReached`/`failed`; on `close()` commit iff EOF-and-no-error else abort; always close the upstream stream and `sink`; idempotent.
- [X] T008 [P] Extend `StubUpstream` in `backend/src/test/kotlin/org/khorum/oss/relikquary/integration/StubUpstream.kt` with a slow-drip mode (flush a body prefix, pause, flush the remainder) and a truncate-midway mode (declare `Content-Length` then close the connection early), without breaking existing callers.

**Checkpoint**: pending-write + tee exist with unit coverage; the test upstream can simulate slow and
truncated transfers.

---

## Phase 3: User Story 1 — Faster cold-cache resolution (Priority: P1) 🎯 MVP

**Goal**: On a proxy cache miss, stream upstream bytes to the client while caching, with no full
pre-buffer and no post-write re-read.

**Independent Test**: cold-cache resolve via `@SpringBootTest` returns a byte-identical artifact, the
cache is populated, and a slow-drip upstream yields first bytes to the client before completion.

- [X] T009 [US1] Write failing integration test `ProxyStreamingCacheIT` in `backend/src/test/kotlin/org/khorum/oss/relikquary/integration/ProxyStreamingCacheIT.kt`: cold-cache resolve returns bytes whose SHA-256 matches the upstream artifact and creates a cache entry (R1); with a slow-drip `StubUpstream`, the client receives initial bytes before the upstream transfer completes (R2); a second resolve is served from cache with no upstream request (FR-007); and a `maven-metadata.xml` request through the proxy is served from upstream and creates **no** cache entry (FR-008/R6 — closes analysis finding C1).
- [X] T010 [US1] Rewrite the non-metadata cache-miss branch of `proxy()` in `backend/src/main/kotlin/org/khorum/oss/relikquary/repository/RepositoryResolver.kt` to tee: `openWrite(cacheKey)` → wrap `upstream.fetch().stream` in `TeeInputStream` → return `Resolution.Hit(StoredArtifact(tee, upstreamLength))`; remove the `storage.write(...)` + `storage.openRead(...)` round-trip. Leave `passThrough` (metadata, R6), the cache-hit branch (R5), and the `NotFound`/`Error` branches (R7/R8) unchanged.
- [X] T011 [US1] Update `resolve()` in `backend/src/main/kotlin/org/khorum/oss/relikquary/protocol/RepositoryController.kt` to set `contentLength(...)` only when the size is known and omit it (chunked) when unknown, for the `Resolution.Hit` branch.
- [X] T012 [US1] Add S3-path coverage to `ProxyStreamingCacheIT` (or a MinIO-backed variant) using Testcontainers MinIO wired via `@DynamicPropertySource`, asserting R1 (byte-identical cached artifact) on the S3 storage backend.
- [X] T013 [US1] Run the US1 tests green and confirm the served response is the fetched (tee) stream — no `storage.openRead` of the just-cached key during the same request (satisfies FR-006 groundwork for US3).

**Checkpoint**: cold-cache resolution streams + caches byte-identically on filesystem and S3 — MVP
deliverable.

---

## Phase 4: User Story 2 — Cache integrity never weakened (Priority: P1)

**Goal**: A client disconnect or mid-stream upstream failure never promotes a truncated artifact.

**Independent Test**: simulate disconnect and upstream truncation; the cache holds zero entries for
the failed artifact and a re-request succeeds.

- [X] T014 [US2] Write failing integration test `ProxyStreamingFailureIT` in `backend/src/test/kotlin/org/khorum/oss/relikquary/integration/ProxyStreamingFailureIT.kt`: a client that disconnects mid-transfer leaves zero cache entries for the artifact (R3); a truncate-midway `StubUpstream` leaves zero cache entries and a subsequent normal request succeeds (R4); the bytes of a *successfully* committed entry equal the upstream (checksum, FR-005).
- [X] T015 [US2] Verify/secure the abort wiring so the tee aborts on premature termination: confirm a client disconnect / broken pipe closes the `TeeInputStream` before EOF (Spring `InputStreamResource` close path) and an upstream `IOException` sets `failed`; adjust `backend/src/main/kotlin/org/khorum/oss/relikquary/proxy/TeeInputStream.kt` (and the resolver if needed) so both paths reach `ArtifactWrite.abort()`.
- [X] T016 [US2] Run the US2 tests green; confirm no temp residue remains after aborted transfers (storage temp cleanup) and no partial cache entry is ever observable.

**Checkpoint**: integrity guarantee proven under disconnect and truncation; no partial promotions.

---

## Phase 5: User Story 3 — No redundant disk re-read on a miss (Priority: P2)

**Goal**: Serving a miss does not re-read the freshly written cache file.

**Independent Test**: resolve an uncached artifact and confirm it is both served and cached without a
second full read of the just-written key in the same request.

- [X] T017 [US3] Write a test asserting the miss path performs no post-write re-read: in `backend/src/test/kotlin/org/khorum/oss/relikquary/unit/` (or an integration test with a counting/spying `ArtifactStorage`), assert `openRead(cacheKey)` is not invoked for the just-cached key during a single cache-miss resolve, while the client still receives the full artifact (FR-006 / SC-005).
- [X] T018 [US3] Confirm `RepositoryResolver.proxy()` returns the tee-backed `StoredArtifact` directly (no `openRead` after commit) in `backend/src/main/kotlin/org/khorum/oss/relikquary/repository/RepositoryResolver.kt`; make T017 green.

**Checkpoint**: one fewer full artifact read per miss, verified.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T019 [P] Run detekt and fix any violations in the new/changed files (`ArtifactWrite.kt`, `TeeInputStream.kt`, the two storage impls, the resolver, the controller).
- [X] T020 [P] Verify Kover coverage includes the tee commit and abort branches, both `openWrite` implementations, and the disconnect/truncation paths; annotate any justified exclusion with `@ExcludeFromCoverage` rather than lowering thresholds.
- [X] T021 Run the proxy/group regression suite — `ProxyResolveTest`, `ProxyRoundTripTest`, `GroupResolveTest`, `ProxyEvictionTest`, `ProxyCentralIT`, `GradleModuleProxyRoundTripTest`, `GradlePluginsProxyResolveTest` — and confirm all stay green (hit/miss/error and group first-match semantics unchanged, FR-010).
- [X] T022 Run `./gradlew :backend:build` and confirm green (compile + detekt + Kover + tests).
- [ ] T023 [P] Manual smoke per `specs/015-proxy-streaming-cache/quickstart.md` against a running sandbox-profile server: cold-cache `time_starttransfer` near upstream TTFB, byte-identity vs upstream checksum, and a second resolve served from cache.
- [X] T024 [P] Update `specs/015-proxy-streaming-cache/spec.md` Status to reflect implementation and note any deviations discovered during build.

---

## Dependencies & Execution Order

- **Setup (T001)** → **Foundational (T002–T008)** → user stories.
- Within Foundational: T002 → T003 → {T004, T005}; T006 → T007; T008 independent.
- **US1 (T009–T013)** depends on Foundational; it is the MVP and lands the tee end-to-end.
- **US2 (T014–T016)** depends on US1 (tee in place); hardens the failure paths.
- **US3 (T017–T018)** depends on US1 (mostly verification; the no-re-read behavior ships in T010).
- **Polish (T019–T024)** after the stories.

```text
T001 → T002 → T003 → T004 ┐
                     → T005 ┤
       T006 → T007         ├─→ US1 (T009→T010→T011→T012→T013)
       T008 ───────────────┘        → US2 (T014→T015→T016)
                                     → US3 (T017→T018)
                                     → Polish (T019…T024)
```

## Parallel Opportunities

- Foundational: T002, T004/T005 (after T003), T006, T008 carry `[P]` — different files.
- Polish: T019, T020, T023, T024 are `[P]`.
- Test tasks (T006, T009, T014, T017) are written before their implementation counterparts (Red→Green).

## Implementation Strategy

- **MVP = User Story 1** (T001–T013): cold-cache streaming + caching on filesystem and S3. Delivers
  the headline latency win on its own.
- **Increment 2 = User Story 2** (T014–T016): prove and secure the no-partial-promotion guarantee.
- **Increment 3 = User Story 3** (T017–T018): lock in the removed re-read.
- Polish closes out gates (detekt/Kover/regression/full build) and validation.

## Independent Test Criteria

- **US1**: cold-cache resolve returns a byte-identical artifact, caches it, streams first bytes before
  completion, and serves the second request from cache (filesystem + S3).
- **US2**: client disconnect and upstream truncation each leave zero cache entries; committed entries
  are byte-identical to the upstream.
- **US3**: a cache-miss resolve serves the full artifact without re-reading the just-written key.
