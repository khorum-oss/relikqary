# Implementation Plan: Proxy (Remote) Repositories & Repository Groups

**Branch**: `claude/spec-006-proxy-repos` | **Date**: 2026-06-28 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/006-proxy-group-repos/spec.md`

## Summary

Add two read-only repository kinds to the existing named-repository model (feature 004): **proxy** repos
that fetch from a configured upstream on a cache miss, store the bytes byte-for-byte, and serve cache
hits without contacting the upstream; and **group** repos that aggregate ordered hosted/proxy members
behind one URL via first-match. Both reject publishing (`405`). Resolution is extracted from
`RepositoryController` into a `RepositoryResolver` that dispatches on a new `kind` field; upstream fetches
use the JDK `HttpClient` (no new dependency). `maven-metadata.xml` is pass-through (never cached);
immutable files are cached permanently. Existing hosted repos, auth, storage backends, and the browse UI
are unchanged.

## Technical Context

**Language/Version**: Kotlin 2.3.21 on the JDK 21 toolchain.

**Primary Dependencies**: Spring Boot 4.1.0 (Web/servlet), Spring Security 7.1.0; **no new runtime
dependency** — upstream fetches use `java.net.http.HttpClient`; the test stub upstream uses
`com.sun.net.httpserver.HttpServer` (both JDK built-ins).

**Storage**: Existing `ArtifactStorage` (filesystem / S3); proxy cache reuses `write`/`openRead` under a
`{proxyName}/{path}` key. No storage interface change.

**Testing**: JUnit 5 + `@SpringBootTest(RANDOM_PORT)` + JDK `HttpClient`; real Maven/Gradle round-trip
through a proxy against a local stub upstream; a guarded real-Maven-Central test (auto-skip offline).

**Target Platform**: Linux server (Spring Boot fat jar).

**Project Type**: Web service (`backend/` module; `frontend/` unaffected by this feature).

**Performance Goals**: Cache hits served from local storage without upstream I/O; streamed bodies (no
whole-file buffering in memory beyond the storage write path).

**Constraints**: Strict Gradle dependency verification stays enabled and **untouched** (no new deps);
detekt zero violations; Kover thresholds hold. Upstream credentials via env/file, never committed.

**Scale/Scope**: One backend module; ~3 new classes (`RepositoryKind`, `UpstreamClient`,
`RepositoryResolver`), extensions to `RepositoryProperties`/`RepositoryRegistry`/`RepositoryController`,
plus default config and docs.

## Constitution Check

*GATE: re-checked after Phase 1 design — PASS.*

- **I. Repository Contract & Client Compatibility** — PASS. Purely additive: new `kind`/`remote*`/`members`
  config (defaults preserve existing `{name, type}`), new repo kinds addressed by the same path scheme.
  No change to the served layout, resolution, or publish acceptance of existing hosted repos. No MAJOR
  bump triggered.
- **II. Test-First & Integration-Verified** — PASS. Real client round-trips through the proxy (local stub
  upstream by default + guarded real Central). The stub is a real in-process HTTP boundary (JDK
  `HttpServer`), analogous to the justified s3mock deviation in feature 003; the guarded real-Central
  test provides true external realism. Unit tests cover resolver dispatch, config validation, and status
  mapping.
- **III. Quality Gates** — PASS. No gate weakened; detekt/Kover unchanged. No new dependency → no
  verification-metadata edit.
- **IV. Supply-Chain Integrity & Faithful Storage** — PASS, reinforced. Cached bytes are stored via the
  existing faithful write path (byte-for-byte, checksums/signatures preserved). Zero new dependencies
  keeps `gradle/verification-metadata.xml` and the trust surface unchanged. Upstream secrets are
  config/env-only.

No violations → Complexity Tracking not required.

## Project Structure

### Documentation (this feature)

```text
specs/006-proxy-group-repos/
├── plan.md              # This file
├── research.md          # Phase 0 decisions (D1–D9)
├── data-model.md        # Entities, resolver, status mapping
├── quickstart.md        # End-to-end validation scenarios
├── contracts/
│   └── proxy-group.md   # Wire + config contract
├── checklists/
│   └── requirements.md  # Spec quality checklist (passing)
└── tasks.md             # Phase 2 output (/speckit-tasks — not created here)
```

### Source Code (repository root)

```text
backend/src/main/kotlin/org/khorum/oss/relikquary/
├── config/
│   └── RepositoryProperties.kt        # MODIFIED: add kind, remoteUrl, remote{User,Pass}, members
├── repository/
│   ├── RepositoryType.kt              # (unchanged: hosted policy enum)
│   ├── RepositoryKind.kt              # NEW: HOSTED | PROXY | GROUP
│   ├── RepositoryRegistry.kt          # MODIFIED: startup validation for proxy/group
│   └── RepositoryResolver.kt          # NEW: read-path dispatcher → Resolution(Hit|Miss|UpstreamError)
├── proxy/
│   └── UpstreamClient.kt              # NEW: JDK HttpClient fetch → UpstreamResponse(Found|NotFound|Error)
├── protocol/
│   ├── RepositoryController.kt        # MODIFIED: GET delegates to resolver; PUT→405 for proxy/group
│   └── BrowseController.kt            # MODIFIED (minor): include kind in repositories list
└── resources/application.yml          # MODIFIED: ship maven-central (proxy) + public (group) defaults

backend/src/test/kotlin/org/khorum/oss/relikquary/
├── unit/
│   ├── RepositoryRegistryTest.kt      # MODIFIED: proxy/group validation cases
│   └── RepositoryResolverTest.kt      # NEW: dispatch + group ordering + status mapping (mocked storage/upstream)
└── integration/
    ├── ProxyResolveTest.kt            # NEW: cache miss→hit, metadata pass-through, 404 vs 502, PUT 405 (stub upstream)
    ├── GroupResolveTest.kt            # NEW: first-match across hosted+proxy members, 404, PUT 405
    ├── ProxyRoundTripTest.kt          # NEW: real Maven/Gradle resolve through proxy (stub) + offline re-resolve
    └── ProxyCentralIT.kt              # NEW: guarded real-Maven-Central proxy resolve (auto-skip offline)
```

**Structure Decision**: Single `backend/` service module (existing). The read path is refactored into
`RepositoryResolver` so proxy/group logic lives in one testable unit instead of branching the controller.
A new `proxy/` package isolates the upstream HTTP concern. The `frontend/` module is untouched (browse UI
keeps working; `kind` surfaced additively in the API).

## Implementation phases (high level)

1. **Config & kind** — `RepositoryKind` enum; extend `RepositoryProperties.Repo`; `RepositoryRegistry`
   startup validation (D2, D3). Unit test the validation matrix.
2. **Upstream client** — `UpstreamClient` over JDK `HttpClient` honoring `ProxySelector` (D1); sealed
   `UpstreamResponse`.
3. **Resolver** — `RepositoryResolver` with `Resolution` sealed type; HOSTED/PROXY/GROUP dispatch,
   metadata pass-through, write-once caching, group first-match + error propagation (D4, D5). Unit test
   with mocked storage/upstream.
4. **Controller wiring** — GET delegates to resolver (map Hit/Miss/UpstreamError → 200/404/502); PUT to
   proxy/group → 405 (D6). `BrowseController` includes `kind` (D9).
5. **Defaults & docs** — `application.yml` ships `maven-central` + `public` (D8); README/quickstart.
6. **Tests & verify** — stub-upstream proxy/group integration + round-trip, guarded real-Central IT
   (D7); `./gradlew build` green; commit & push.

## Complexity Tracking

No constitution violations; section intentionally empty.
