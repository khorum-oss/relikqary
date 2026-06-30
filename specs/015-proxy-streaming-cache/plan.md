# Implementation Plan: Streaming Proxy Cache (Tee)

**Branch**: `015-proxy-streaming-cache` | **Date**: 2026-06-29 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/015-proxy-streaming-cache/spec.md`

## Summary

On a proxy cache miss the resolver currently does `upstream.fetch()` → `storage.write()` (fully
download + persist) → `storage.openRead()` (re-read) → serve. The client receives nothing until the
artifact is fully downloaded and re-read. This feature changes the miss path to **tee**: the upstream
body is streamed to the client while being mirrored to a pending cache location, and the response is
served from those fetched bytes (no post-write re-read). The pending location is promoted to a real
cache entry **only on a fully successful transfer** (EOF reached) via the existing temp-file +
atomic-move pattern; a client disconnect or mid-stream upstream failure aborts and discards it, so a
truncated artifact never becomes a cache entry. `maven-metadata.xml` pass-through is unchanged.

## Technical Context

**Language/Version**: Kotlin, JDK 21 toolchain

**Primary Dependencies**: Spring Boot (Web MVC, servlet/Tomcat), `java.net.http.HttpClient`
(existing `UpstreamClient`), existing `ArtifactStorage` abstraction (filesystem + S3)

**Storage**: Pluggable `ArtifactStorage` (FilesystemArtifactStorage, S3ArtifactStorage); cache writes
use a temp-then-promote pattern

**Testing**: JUnit5 + `@SpringBootTest`; real client round-trips; in-process `StubUpstream`
(`com.sun.net.httpserver.HttpServer`) extended to simulate slow / truncating / disconnecting
transfers; Testcontainers MinIO for the S3 storage path

**Target Platform**: Linux server (Spring Boot app)

**Project Type**: Web service (backend module) — single affected module: `backend`

**Performance Goals**: Cold-cache time-to-first-byte ≈ upstream TTFB (no full server pre-buffer);
≈50% per-artifact latency reduction on transfer-dominated misses; one fewer full artifact read per
miss

**Constraints**: Byte-for-byte cache integrity (constitution IV); zero partial-artifact promotions;
no change to the observable repository contract (hit/miss/error status, resolved bytes, group
first-match, per-repo read authz)

**Scale/Scope**: One resolve path (`RepositoryResolver.proxy`), one storage-abstraction addition
(pending write), one stream wrapper (tee), controller content-length handling for unknown-length
streams. No new module.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Repository Contract & Client Compatibility** — PASS. The resolved bytes and hit/miss/error
  status codes are unchanged (FR-010); only the timing and internal I/O of a miss change. No layout,
  resolution, publish, or config-contract change ⇒ no MAJOR bump. The one outward-visible difference
  is that an unknown-length streamed response may use chunked transfer-encoding instead of a
  `Content-Length` header — a standard, client-compatible HTTP behavior.
- **II. Test-First & Integration-Verified Discipline** — PASS (enforced by task ordering). New
  behavior gets failing tests first: a `@SpringBootTest` proxy round-trip proving a cold-cache resolve
  returns byte-identical content and caches it; integration tests for client-disconnect and
  mid-stream upstream truncation proving **nothing** is cached; the S3 storage path exercised via
  Testcontainers MinIO. Streaming/overlap is asserted behaviorally (a slow upstream yields first
  bytes to the client before the transfer completes), not via timing flakiness.
- **III. Quality Gates Are Non-Negotiable** — PASS. detekt zero violations; Kover covers the tee,
  the pending-write commit/abort branches, and the disconnect/truncation paths; no SonarCloud
  regression. No gate weakened.
- **IV. Supply-Chain Integrity & Faithful Storage** — PASS, and central to this feature. Cached
  bytes remain byte-for-byte identical to the upstream (FR-005); the promote-only-on-complete rule
  (FR-002/003/004) strictly preserves "never serve a corrupt/truncated artifact." No checksum/
  signature is altered or stripped. No dependency added ⇒ no `verification-metadata.xml` change.

**Result**: PASS. No violations ⇒ Complexity Tracking not required.

## Project Structure

### Documentation (this feature)

```text
specs/015-proxy-streaming-cache/
├── plan.md              # This file
├── research.md          # Phase 0 — design decisions
├── data-model.md        # Phase 1 — entities/abstractions
├── quickstart.md        # Phase 1 — validation scenarios
├── contracts/
│   └── proxy-streaming-cache.md   # storage pending-write + tee behavior contract
└── checklists/
    └── requirements.md  # spec quality checklist (from /speckit-specify)
```

### Source Code (repository root)

```text
backend/src/main/kotlin/org/khorum/oss/relikquary/
├── storage/
│   ├── ArtifactStorage.kt          # + openWrite(key): ArtifactWrite (pending write handle)
│   ├── ArtifactWrite.kt            # NEW — Closeable handle: sink OutputStream, commit()/abort()
│   ├── FilesystemArtifactStorage.kt# implement openWrite via temp file + atomic-move-on-commit
│   └── S3ArtifactStorage.kt        # implement openWrite via temp file + upload-on-commit
├── proxy/
│   └── TeeInputStream.kt           # NEW — mirrors read bytes to an ArtifactWrite; commit on EOF,
│                                   #        abort on early close / read error
├── repository/
│   └── RepositoryResolver.kt       # proxy(): replace write+openRead with tee-on-fetch
└── protocol/
    └── RepositoryController.kt     # omit Content-Length when streamed length is unknown

backend/src/test/kotlin/org/khorum/oss/relikquary/
├── integration/
│   ├── StubUpstream.kt             # extend: slow-drip, truncate-midway, never-finish modes
│   ├── ProxyStreamingCacheIT.kt    # NEW — cold-cache resolve streams + caches byte-identically
│   └── ProxyStreamingFailureIT.kt  # NEW — client disconnect / upstream truncation ⇒ nothing cached
└── unit/
    └── TeeInputStreamTest.kt       # NEW — EOF⇒commit, early-close⇒abort, read-error⇒abort
```

**Structure Decision**: Single affected module (`backend`). The change is confined to the storage
abstraction (one new method + a small handle type), one stream wrapper, the proxy resolve path, and a
controller content-length tweak. No new Gradle module; no `settings.gradle.kts` change.

## Complexity Tracking

> No constitution violations — section intentionally empty.
