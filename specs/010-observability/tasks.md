---
description: "Task list for observability & operational readiness"
---

# Tasks: Observability & Operational Readiness

**Input**: `specs/010-observability/`. **Tests**: included (Constitution Principle II — real round trips
over HTTP + storage). Backend-only; main paths under `backend/src/main/kotlin/org/khorum/oss/relikquary/`,
tests under `backend/src/test/kotlin/org/khorum/oss/relikquary/`. **Two new dependencies**
(`spring-boot-starter-actuator`, `micrometer-registry-prometheus`) → `verification-metadata.xml`
regenerated.

## Phase 1: Setup

- [X] T001 Add `spring-boot-starter-actuator` and `micrometer-registry-prometheus` (BOM-managed, no
  explicit version) to `gradle/libs.versions.toml` `[libraries]` and wire both as `implementation` in
  `backend/build.gradle.kts`, mirroring the existing Spring starters.
- [X] T002 Regenerate `gradle/verification-metadata.xml` for the two new deps via
  `./gradlew --write-verification-metadata sha256 build` (verification stays enabled; metadata extended,
  never disabled).
- [X] T003 Add `observability/ObservabilityProperties.kt`
  (`@ConfigurationProperties("relikquary.observability")`: `requestLog.enabled=false`,
  `requestLog.includeQueryString=false`, `storageProbeTtl=PT2S`, `upstreamHealthTtl=PT30S`,
  `storageUsageRefresh=PT5M`); register it in `RelikquaryApplication.kt` `@EnableConfigurationProperties`.
- [X] T004 Configure `management.*` in `backend/src/main/resources/application.yml`: expose
  `health,info,prometheus,metrics`; `endpoint.health.probes.enabled=true`; `show-details=always` +
  `show-components=always`; health groups `liveness=livenessState` and `readiness=readinessState,storage`;
  custom status `order: DOWN,OUT_OF_SERVICE,DEGRADED,UP,UNKNOWN` and `http-mapping.DEGRADED=200`; document
  the `relikquary.observability.*` defaults. Add `observability/ObservabilityConfig.kt` only if status
  registration needs code beyond YAML.

**Checkpoint**: actuator + Prometheus on the classpath; probes/prometheus endpoints resolve; config binds.

## Phase 2: Foundational (blocking prerequisites for all stories)

- [X] T005 Gate the management surface in `config/SecurityConfig.kt` (security-enabled branch, before
  `anyRequest().access(authorizationManager)`): `requestMatchers("/actuator/health/liveness",
  "/actuator/health/readiness").permitAll()` then `requestMatchers("/actuator/**").hasRole("PUBLISH")`.
  Leave `anyRequest().access(authorizationManager)` and the disabled-security `permitAll` branch unchanged
  (open when security is off).
- [X] T006 [P] Integration test `integration/OperationalAuthTest.kt` (`@SpringBootTest(RANDOM_PORT)` +
  `HttpClient`, authz profile): `/actuator/health/liveness` + `/readiness` → 200 with no credentials;
  `/actuator/prometheus`, `/actuator/metrics`, `/actuator/health` (root) → 401 anon, 403
  authenticated-non-publisher, 200 publisher; with `relikquary.security.enabled=false` all are open.

**Checkpoint**: probes are public and every other operational endpoint is operator-gated (FR-006, SC-004).

## Phase 3: User Story 1 — Health & readiness for orchestrators (Priority: P1)

**Goal**: Liveness reflects process health; readiness reflects storage reachability; a detailed health
view surfaces storage + proxy-upstream state, with upstream outages degraded but non-gating.

**Independent test**: Probe liveness/readiness with no credentials → UP/200; break storage → readiness
503, restore → 200; with a down proxy upstream the detailed health is DEGRADED while probes stay UP.

- [X] T007 [US1] Add `probe(): StorageProbe` to `storage/ArtifactStorage.kt` and the `StorageProbe(healthy:
  Boolean, backend: String, detail: String?)` type (side-effect-free; never returns secrets).
- [X] T008 [US1] Implement `probe` in `storage/FilesystemArtifactStorage.kt` (`isDirectory(root) &&
  isWritable(root)`, no file written, `backend="filesystem"`) and `storage/S3ArtifactStorage.kt`
  (`headBucket`, no object written, `backend="s3"`; non-secret `detail` on failure).
- [X] T009 [P] [US1] Unit test `unit/StorageProbeTest.kt` (`@TempDir`): writable root ⇒ healthy; missing/
  non-writable root ⇒ not healthy with a non-secret detail; backend label correct.
- [X] T010 [P] [US1] Integration test `integration/S3StorageProbeTest.kt` (s3mock harness): probe against a
  live bucket ⇒ healthy; against an unreachable endpoint ⇒ not healthy (FR-008 parity).
- [X] T011 [US1] Add `observability/health/StorageHealthIndicator.kt` (Actuator `HealthIndicator`, bean id
  `storage`): `UP` when `probe().healthy`, else `DOWN` with the non-secret detail; TTL-cached per
  `storageProbeTtl`. (Joins the readiness group via the T004 config.)
- [X] T012 [US1] Add `observability/health/UpstreamHealthIndicator.kt` (bean id `upstreams`): per-PROXY-repo
  reachability (HEAD/short-timeout to `remoteUrl`, reusing the `UpstreamClient` HttpClient style),
  `UP` when all reachable else custom `DEGRADED`; TTL-cached per `upstreamHealthTtl`; detail is
  `{repo: {reachable}}` only — no `remoteUsername`/`remotePassword`. Not a member of liveness/readiness.
- [X] T013 [US1] Integration test `integration/ProbesTest.kt`: liveness + readiness return UP/200 with no
  credentials; pointing storage at a broken root makes readiness `DOWN`/503 while liveness stays UP;
  restoring storage recovers readiness to 200 (FR-002, SC-001); assert the detailed `/actuator/health`
  output contains none of the *actual configured* secrets — the S3 `accessKey`/`secretKey` and any proxy
  `remoteUsername`/`remotePassword` — not merely arbitrary strings (FR-009).
- [X] T014 [US1] Integration test `integration/UpstreamHealthTest.kt` (StubUpstream stopped/failing): the
  detailed `/actuator/health` reports `upstreams` DEGRADED and overall DEGRADED at HTTP 200, while
  `/actuator/health/readiness` and `/liveness` stay UP/200 (FR-003, SC-005).

**Checkpoint**: orchestrators can probe; storage gates readiness; upstream outages degrade without gating.

## Phase 4: User Story 2 — Metrics for monitoring (Priority: P1)

**Goal**: Scrape Prometheus-format metrics for HTTP rate/latency/error, publish/resolve counts, proxy
cache hit/miss + upstream outcomes, cleanup outcomes, and storage usage.

**Independent test**: Drive publishes, resolves, a proxy miss-then-hit, and a cleanup run, then scrape
`/actuator/prometheus` and confirm the corresponding counters/timers/gauges are present and increasing.

- [X] T015 [US2] Add `observability/metrics/RepositoryMetrics.kt` (wraps `MeterRegistry`) with
  `recordPublish(repo, outcome)`, `recordResolve(repo, outcome)`, `recordCache(repo, result)`,
  `recordUpstream(repo, outcome)` → counters `relikquary.publish`/`resolve`/`proxy.cache`/`proxy.upstream`.
- [X] T016 [US2] Inject `RepositoryMetrics` into `protocol/RepositoryController.kt`; record publish outcome
  (accepted/rejected) in `publish` and resolve outcome (hit/miss/upstream_error) in `resolve`.
- [X] T017 [US2] Inject `RepositoryMetrics` into `repository/RepositoryResolver.kt`; in `proxy` record the
  cache `hit`/`miss` and the upstream `found`/`not_found`/`error` outcome at the existing seam.
- [X] T018 [US2] Add `observability/metrics/CleanupMetricsRecorder.kt` and invoke it at the end of
  `cleanup/CleanupService.kt` `run(dryRun)` with the `CleanupReport`: increment
  `relikquary.cleanup.items.removed`, `relikquary.cleanup.bytes.reclaimed`, and
  `relikquary.cleanup.runs{dry_run}`.
- [X] T019 [US2] Add `observability/metrics/StorageUsageMetrics.kt` (Micrometer `MeterBinder`) registering
  `relikquary.storage.usage.bytes{backend}` and `relikquary.storage.objects{backend}` gauges backed by a
  cached total from `storage.walk("")`, refreshed every `storageUsageRefresh` (lazy first read; never a
  per-scrape walk).
- [X] T020 [P] [US2] Integration test `integration/MetricsScrapeTest.kt`: publish to a hosted repo, resolve
  it, drive a proxy cache miss then hit (StubUpstream), run `CleanupService.run`, then GET
  `/actuator/prometheus` (as publisher) and assert `http_server_requests_*`, `relikquary_publish_total`,
  `relikquary_resolve_total`, `relikquary_proxy_cache_total{result="hit"|"miss"}`,
  `relikquary_proxy_upstream_total`, `relikquary_cleanup_items_removed_total`,
  `relikquary_cleanup_bytes_reclaimed_total`, and `relikquary_storage_usage_bytes` are present and reflect
  the traffic (FR-004, SC-002); assert the scrape body contains none of the *actual configured* secrets
  (S3 `accessKey`/`secretKey`, proxy `remoteUsername`/`remotePassword`, user passwords) (FR-009).

**Checkpoint**: a monitoring system can scrape the full metric set in Prometheus format.

## Phase 5: User Story 3 — Structured request logging (Priority: P2)

**Goal**: One structured JSON log line per request (method, repository, path, status, bytes, duration,
principal), opt-in and off by default.

**Independent test**: Enable the flag, make a request, and confirm exactly one JSON line with the
documented fields; an authenticated request includes the principal, an anonymous one omits it; with the
flag off, no such line is emitted.

- [X] T021 [US3] Add `observability/logging/RequestLogEvent.kt` (the record: `method`, `repository?`,
  `path`, `status`, `bytes`, `durationMs`, `principal?`) and
  `observability/logging/CountingResponseWrapper.kt` (counts bytes written via the output stream/writer
  without buffering the body; falls back to `Content-Length`).
- [X] T022 [US3] Add `observability/logging/RequestLoggingFilter.kt` (`OncePerRequestFilter`,
  `@ConditionalOnProperty("relikquary.observability.request-log.enabled")`): times the chain, derives the
  repository from the first decoded path segment (excluding `actuator`/`api`/`ui`), reads the principal
  from `SecurityContextHolder` (omitted when anonymous), and logs one Jackson-serialized JSON line on the
  `relikquary.access` logger at INFO.
- [X] T023 [P] [US3] Unit test `unit/RequestLogEventTest.kt`: field extraction + repository parsing
  (repo path vs `/actuator` vs `/api`), principal present vs absent, and the serialized JSON is a single
  line with exactly the documented keys.
- [X] T024 [US3] Integration test `integration/RequestLogTest.kt` (attach a Logback `ListAppender` to the
  `relikquary.access` logger): with the flag on, a resolve emits exactly one JSON line carrying method/
  repository/path/status/bytes/duration; an authenticated publish includes `principal`, an anonymous
  request omits it; with the flag off (default) no line is emitted (FR-005, SC-003). Include a case where
  the response sets `Content-Length` without streaming a body, asserting the logged `bytes` uses the
  header-fallback path (T021).

**Checkpoint**: operators can ship/query one structured line per request; default logging unchanged.

## Phase 6: Polish & verify

- [X] T025 [P] Document the observability surface in `backend/src/main/resources/application.yml` comments
  and add an Observability section to `README.md`: liveness/readiness probes, the Prometheus scrape and
  the metric catalog, the opt-in request log, and the operator-gating model (probes open, rest `PUBLISH`).
- [X] T026 `./gradlew build` green — detekt zero (suppress intentional probe `SwallowedException` where
  needed; lines ≤140), Kover holds, all new unit/integration tests pass, and the existing publish/resolve
  real-client round-trips are unchanged (SC-006); `verification-metadata.xml` reflects only the two new
  deps. Commit & push to `claude/spec-010-observability`.

## Dependencies

Setup (T001–T004) blocks everything; T002 depends on T001. Foundational T005 depends on T001 (actuator
present); T006 depends on T005. **US1** (T007–T014) depends on Setup+Foundational: T008 depends on T007;
T009/T010 on T008; T011/T012 on T007–T008 and the T004 group config; T013/T014 on T011/T012. **US2**
(T015–T020): T016/T017 depend on T015; T020 on T015–T019. **US3** (T021–T024): T022 depends on T021; T024
on T022. US1/US2/US3 are independent of each other after Foundational. Polish (T025–T026) last.

## Parallel opportunities

- T009 ∥ T010 (storage-probe unit vs S3 parity, different files). T006 runs alongside US1 work once T005
  lands. T020 (metrics scrape) and T023 (request-log unit) are independent across stories. T025 ∥ the test
  tasks.
- Within US2, the four recorder/binder additions (T015, T018, T019) touch different files and can be
  authored in parallel; T016/T017 must follow T015.

## MVP scope

Setup + Foundational + **US1** (health/readiness) is the MVP — it makes Relikquary safely deployable under
an orchestrator. **US2** (metrics) is the equally-P1 companion for monitoring; **US3** (structured request
logging, P2) adds shippable per-request audit logs.
