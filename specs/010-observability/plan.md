# Implementation Plan: Observability & Operational Readiness

**Branch**: `claude/spec-010-observability` | **Date**: 2026-06-28 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/010-observability/spec.md`

## Summary

Add an operational surface to Relikquary: (1) **liveness/readiness probes** for orchestrators — liveness
reflects process health, readiness reflects whether the active storage backend (filesystem or S3) is
reachable/writable; a richer health view also surfaces proxy-upstream reachability as *degraded* without
gating readiness. (2) **Prometheus-format metrics** — HTTP request rate/latency/error (Micrometer's
built-in `http.server.requests`), publish/resolve counts, proxy cache hit/miss + upstream-fetch outcomes,
cleanup items-removed/bytes-reclaimed, and storage usage. (3) An **opt-in structured (JSON) per-request
log line** (method, repository, path, status, bytes, duration, principal). Probes stay open; every other
operational endpoint is operator-gated (global `PUBLISH`) when security is enabled, open when disabled.
Delivered via Spring Boot Actuator + Micrometer (Prometheus registry) — two new dependencies added
through the project's dependency-verification process. Publish/resolve/auth behaviour and the Maven
client contract are unchanged; both storage backends behave consistently.

## Technical Context

**Language/Version**: Kotlin 2.3.21 on JDK 21.

**Primary Dependencies**: Spring Boot 4.1.0 (Web, Security, **Actuator** — new), Micrometer
(**`micrometer-registry-prometheus`** — new), Jackson (already present, for the JSON request line). Both
new deps are BOM-managed (no explicit version) and recorded in `gradle/verification-metadata.xml`.

**Storage**: Existing `ArtifactStorage` (filesystem / S3) gains a cheap `probe()` reachability/writability
check that backs the storage health indicator. No stored bytes are read or altered by a probe.

**Testing**: JUnit 5 unit tests (storage probe, request-log event) + `@SpringBootTest(RANDOM_PORT)` +
JDK `HttpClient` integration (probes, metrics scrape, operational-endpoint auth matrix, upstream-degraded
health, request-log capture via a Logback list appender); S3 probe exercised against the s3mock harness.

**Target Platform**: Linux server (Spring Boot fat jar). `frontend/` unaffected.

**Project Type**: Web service (`backend/` module).

**Performance Goals**: No added work on the publish/resolve hot paths beyond cheap counter increments. The
storage readiness probe is a single lightweight reachability check (filesystem stat/writability or one S3
`headBucket`), optionally short-TTL cached. The storage-usage gauge is served from a periodically-refreshed
cached total, never a per-scrape full `walk`.

**Constraints**: detekt zero, Kover holds, SonarCloud not regressed. Strict dependency verification stays
enabled — the two new deps are added by **extending** `gradle/verification-metadata.xml`, never by
disabling it. Health/metrics output MUST NOT expose secrets (storage/upstream credentials, passwords).

**Scale/Scope**: One backend module; ~11 new classes (observability metrics recorder, storage-usage
binder, cleanup metrics recorder, two health indicators, request-logging filter + counting wrapper +
event, observability properties, an observability config), `ArtifactStorage.probe` on both backends, a
SecurityConfig change for actuator exposure, instrumentation hooks in the resolver/controller/upstream/
cleanup seams, and config/docs.

## Constitution Check

*GATE: re-checked after Phase 1 design — PASS.*

- **I. Repository Contract & Client Compatibility** — PASS. Purely additive: new `/actuator/**`
  management surface plus opt-in logging; the Maven wire layout, resolution, and publish acceptance are
  untouched. No configuration contract is removed (new `management.*` and `relikquary.observability.*`
  keys only). FR-007 keeps publish/resolve/auth behaviour identical whether observability is on or off.
- **II. Test-First & Integration-Verified** — PASS. Real round-trips over the HTTP boundary: probes and
  metrics are scraped over real HTTP; storage-down readiness is exercised against a real broken backend;
  the existing publish/resolve real-client round-trips must stay green (SC-006). The S3 storage probe is
  exercised against the real s3mock boundary (FR-008 parity), Testcontainers/MinIO where Docker exists.
- **III. Quality Gates** — PASS. detekt/Kover/Sonar unchanged and not weakened. Two new dependencies →
  `gradle/verification-metadata.xml` regenerated (`--write-verification-metadata sha256`), as in 002/003.
- **IV. Supply-Chain Integrity & Faithful Storage** — PASS, reinforced. A storage probe never rewrites or
  re-checksums artifacts (filesystem: stat/writability; S3: `headBucket` — no test-write into the bucket).
  Health and metrics output is curated to exclude secrets (FR-009). New deps go through verification.

No violations → Complexity Tracking not required.

## Project Structure

### Documentation (this feature)

```text
specs/010-observability/
├── plan.md, research.md, data-model.md, quickstart.md
├── contracts/observability.md
├── checklists/requirements.md
└── tasks.md   # Phase 2 (/speckit-tasks — not created here)
```

### Source Code (repository root)

```text
backend/src/main/kotlin/org/khorum/oss/relikquary/
├── observability/
│   ├── ObservabilityProperties.kt        # NEW: relikquary.observability (requestLog.enabled, TTLs)
│   ├── ObservabilityConfig.kt            # NEW: MeterBinder wiring + DEGRADED status registration
│   ├── metrics/
│   │   ├── RepositoryMetrics.kt          # NEW: publish/resolve/cache/upstream counters
│   │   ├── StorageUsageMetrics.kt        # NEW: MeterBinder — cached storage-usage gauges
│   │   └── CleanupMetricsRecorder.kt     # NEW: records CleanupReport totals into meters
│   ├── health/
│   │   ├── StorageHealthIndicator.kt     # NEW: readiness — storage reachable/writable
│   │   └── UpstreamHealthIndicator.kt    # NEW: detail-only — proxy upstreams (DEGRADED, TTL-cached)
│   └── logging/
│       ├── RequestLoggingFilter.kt       # NEW: opt-in OncePerRequestFilter, one JSON line/request
│       ├── CountingResponseWrapper.kt    # NEW: counts response bytes without buffering
│       └── RequestLogEvent.kt            # NEW: the structured per-request record (Jackson)
├── storage/
│   ├── ArtifactStorage.kt                # MODIFIED: add probe(): StorageProbe
│   ├── FilesystemArtifactStorage.kt      # MODIFIED: probe via directory stat + writability
│   └── S3ArtifactStorage.kt              # MODIFIED: probe via headBucket
├── protocol/
│   └── RepositoryController.kt           # MODIFIED: record publish/resolve outcomes
├── repository/
│   └── RepositoryResolver.kt             # MODIFIED: record proxy cache hit/miss + upstream outcome
├── cleanup/
│   └── CleanupService.kt                 # MODIFIED: record cleanup metrics post-run
├── config/
│   └── SecurityConfig.kt                 # MODIFIED: actuator matchers (probes open, rest PUBLISH)
├── RelikquaryApplication.kt              # MODIFIED: register ObservabilityProperties
└── resources/application.yml             # MODIFIED: management.* + relikquary.observability

backend/src/test/kotlin/org/khorum/oss/relikquary/
├── unit/
│   ├── StorageProbeTest.kt               # NEW: filesystem probe up/down (@TempDir)
│   └── RequestLogEventTest.kt            # NEW: field extraction + JSON shape, principal present/absent
└── integration/
    ├── ProbesTest.kt                     # NEW: liveness/readiness open + storage-down flips readiness
    ├── MetricsScrapeTest.kt              # NEW: drive traffic → /actuator/prometheus has the meters
    ├── OperationalAuthTest.kt            # NEW: probes open; metrics/detailed health 401/403/200; disabled⇒open
    ├── UpstreamHealthTest.kt             # NEW: down upstream ⇒ health DEGRADED, probes stay UP
    ├── RequestLogTest.kt                 # NEW: opt-in JSON line per request; principal present/absent
    └── S3StorageProbeTest.kt            # NEW: S3 probe reachable against s3mock (FR-008 parity)
```

**Structure Decision**: A new `observability/` package groups the three concerns (metrics, health,
logging) behind small, testable units; controllers/resolver/cleanup gain thin recording calls at the
already-identified seams. `ArtifactStorage.probe` is the one storage addition (a cheap reachability check
both backends implement). Probe/readiness wiring uses Actuator health groups; operator gating is added to
the existing single `SecurityFilterChain`, keeping `RepositoryAuthorizationManager` focused on the
Maven/`/api` surface. `frontend/` is untouched.

## Implementation phases (high level)

1. **Dependencies & exposure** — add `spring-boot-starter-actuator` + `micrometer-registry-prometheus`
   to the catalog/build; regenerate `verification-metadata.xml`; configure `management.*` (expose health
   + prometheus + metrics + info; probes enabled; health groups; DEGRADED status mapping); register
   `ObservabilityProperties`.
2. **Storage probe & health** — `ArtifactStorage.probe()` on both backends; `StorageHealthIndicator`
   (in the readiness group); `UpstreamHealthIndicator` (detail-only, TTL-cached, DEGRADED on outage).
3. **Metrics** — `RepositoryMetrics` (publish/resolve/cache/upstream) wired into the controller/resolver/
   upstream seams; `CleanupMetricsRecorder` invoked from `CleanupService.run`; `StorageUsageMetrics`
   gauge from a periodically-refreshed cached total.
4. **Operator gating** — SecurityConfig: liveness/readiness `permitAll`; all other `/actuator/**`
   `hasRole(PUBLISH)`; `anyRequest().access(manager)` unchanged; disabled-security branch leaves all open.
5. **Structured request log** — opt-in `RequestLoggingFilter` + `CountingResponseWrapper` + `RequestLogEvent`
   emitting one JSON line per request (off by default).
6. **Integration & verify** — probes/metrics/auth-matrix/upstream-degraded/request-log + S3 probe parity;
   existing round-trips stay green; `./gradlew build` green (verification-metadata extended); README;
   commit & push.

## Complexity Tracking

No constitution violations; section intentionally empty.
