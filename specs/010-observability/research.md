# Research: Observability & Operational Readiness

Phase 0 decisions for feature 010. Resolves the technical unknowns behind the plan. Three product-level
decisions were already fixed in `/speckit-clarify` (Prometheus endpoint; opt-in JSON request log;
only probes public) — this document records the *how*.

## D1 — Probe & health framework: Spring Boot Actuator health groups

**Decision**: Use Spring Boot Actuator's built-in **liveness/readiness probe support** and **health
groups** rather than hand-rolling probe endpoints.

- Enable probes: `management.endpoint.health.probes.enabled=true`. Actuator then serves
  `/actuator/health/liveness` and `/actuator/health/readiness`, each backed by a health *group*.
- Liveness group = `livenessState` (Spring's `ApplicationAvailability`) only — process health, never
  gated on storage (a storage outage must not kill/restart the pod).
- Readiness group = `readinessState` **+ a custom `storage` indicator** (D2). When storage is
  unreachable, the readiness group aggregates to `DOWN` → the probe returns HTTP `503`; it recovers to
  `200` when storage returns (FR-002, SC-001).

**Rationale**: Actuator is the de-facto Spring operational surface; groups give exactly the
"liveness ≠ readiness, readiness includes storage" semantics declaratively. No custom controllers.

**Alternatives considered**: custom `@RestController` probes (re-implements aggregation, more code,
diverges from orchestrator expectations); putting storage in liveness (wrong — would restart a pod on a
transient storage blip instead of just removing it from rotation).

## D2 — Storage readiness: `ArtifactStorage.probe()`

**Decision**: Add a cheap `probe(): StorageProbe` to the `ArtifactStorage` interface; a
`StorageHealthIndicator` (Actuator `HealthIndicator`, bean id `storage`) calls it and maps the result to
`UP`/`DOWN`. The indicator joins the readiness group.

- **Filesystem**: the root directory exists, is a directory, and `Files.isWritable(root)` — a stat-level
  check, no test file written (faithful storage; no churn).
- **S3**: a single `headBucket` (or `listObjectsV2` with `maxKeys=1`) — proves endpoint reachability and
  that credentials are valid. **No** test object is written into the bucket (Principle IV — never mutate
  the store for a probe; avoids polluting consumers' buckets).
- `StorageProbe(healthy: Boolean, backend: String, detail: String?)` — `backend` is `"filesystem"`/`"s3"`;
  `detail` is a short non-secret reason on failure. **No** credentials, endpoint secrets, or keys ever
  appear (FR-009). The S3 probe deliberately does not echo the bucket endpoint/region beyond the backend
  label.

**Rationale**: keeps the indicator backend-agnostic and the "reachable/writable" semantics adapt per
backend (FR-008). The check is O(1) and side-effect-free, satisfying the lightweight-probe assumption.

**Alternatives considered**: writing+deleting a marker object each probe (mutates the store every ~10s,
violates faithful-storage spirit and adds S3 cost); reusing `list("")` (lists root children — fine for
filesystem, but for S3 a bucket-root list is heavier and still doesn't prove write perms any better than
`headBucket`).

**Probe cost / caching**: a short TTL (default `PT2S`, `relikquary.observability.storage-probe-ttl`)
caches the last probe so a burst of readiness scrapes collapses to one backend call; still fresh enough
for orchestrator cadence.

## D3 — Proxy-upstream health: detail-only, DEGRADED, never gating

**Decision**: A `UpstreamHealthIndicator` checks each PROXY repo's `remoteUrl` reachability and reports a
**custom `DEGRADED` status** when one or more upstreams are unreachable. It is **not** a member of the
liveness or readiness groups, so it never affects the probes (FR-003, SC-005).

- Status wiring: register `DEGRADED` in `management.endpoint.health.status.order` between `OUT_OF_SERVICE`
  and `UP` (severity: `DOWN` > `OUT_OF_SERVICE` > `DEGRADED` > `UP` > `UNKNOWN`), and map
  `management.endpoint.health.status.http-mapping.DEGRADED=200` so the detailed `/actuator/health`
  endpoint returns `200` (degraded, not failed) on an upstream outage — monitoring sees the degraded
  status without the endpoint flapping to `503`.
- Reachability check = a `HEAD` (fallback `GET` with tiny range) to the upstream base, short timeout,
  reusing the JDK `HttpClient` style from `UpstreamClient`. Result TTL-cached
  (`relikquary.observability.upstream-health-ttl`, default `PT30S`) so health scrapes don't hammer
  upstreams; a transient blip self-heals on the next refresh.
- Detail lists each proxy repo name + `reachable: true|false` only — **no** `remoteUsername`/
  `remotePassword` (FR-009).

**Rationale**: surfaces operator-useful upstream state in the health view while strictly isolating it from
the serve/ready decision, exactly as the spec requires.

**Alternatives considered**: marking upstream `DOWN` (would make overall health `503`, conflating a proxy
outage with instance failure); putting it in readiness (would pull the instance from rotation on an
upstream blip — explicitly forbidden by FR-003).

## D4 — Metrics: Micrometer + Prometheus registry

**Decision**: Add `micrometer-registry-prometheus`; expose `/actuator/prometheus`. HTTP rate/latency/error
come **for free** from Micrometer's auto-configured `http.server.requests` timer (tags: `method`,
`uri`, `status`, `outcome`) — no custom HTTP instrumentation needed (FR-004 HTTP metrics). Custom meters,
recorded at the seams the Explore pass identified:

| Meter | Type | Tags | Seam |
|-------|------|------|------|
| `relikquary.publish` | Counter | `repository`, `outcome` (accepted/rejected) | `RepositoryController.publish` |
| `relikquary.resolve` | Counter | `repository`, `outcome` (hit/miss/upstream_error) | `RepositoryController.resolve` |
| `relikquary.proxy.cache` | Counter | `repository`, `result` (hit/miss) | `RepositoryResolver.proxy` |
| `relikquary.proxy.upstream` | Counter | `repository`, `outcome` (found/not_found/error) | `RepositoryResolver.proxy` |
| `relikquary.cleanup.items.removed` | Counter | — | `CleanupService.run` (increment by report total) |
| `relikquary.cleanup.bytes.reclaimed` | Counter | — | `CleanupService.run` |
| `relikquary.cleanup.runs` | Counter | `dry_run` (true/false) | `CleanupService.run` |
| `relikquary.storage.usage.bytes` | Gauge | `backend` | cached total from `walk("")` |
| `relikquary.storage.objects` | Gauge | `backend` | cached object count |

- A thin `RepositoryMetrics` component wraps `MeterRegistry` with `recordPublish/recordResolve/
  recordCache/recordUpstream`, injected into the controller/resolver so call sites stay one-liners.
- `CleanupMetricsRecorder` is invoked at the end of `CleanupService.run(dryRun)` with the produced
  `CleanupReport` (covers both scheduled and on-demand runs from the single seam).
- `StorageUsageMetrics` is a Micrometer `MeterBinder` registering gauges that read a **cached** total
  (refreshed every `relikquary.observability.storage-usage-refresh`, default `PT5M`, plus a lazy first
  read). A full `walk` is never on the scrape path (perf/Principle: no hot-path cost).

**Rationale**: Prometheus is the format operators expect (clarified); Micrometer gives HTTP metrics and a
clean custom-meter API with near-zero overhead (counter increments). Counters are monotonic (correct for
rates); usage is a gauge.

**Alternatives considered**: generic `/actuator/metrics` only (clarified against — needs a bridge to
scrape); per-scrape storage `walk` (O(store) on every scrape — rejected on cost); a `@Timed` aspect for
publish/resolve (the `http.server.requests` timer already covers latency; explicit counters give the
domain outcomes that the HTTP timer's status tag can't, e.g. hit vs miss).

## D5 — Structured request log: opt-in JSON via a servlet filter

**Decision**: A `RequestLoggingFilter` (`OncePerRequestFilter`), registered **only** when
`relikquary.observability.request-log.enabled=true` (`@ConditionalOnProperty`, default false → zero
overhead when off, FR-005). After the chain completes it logs exactly one line to a dedicated logger
(`relikquary.access`) at INFO: a Jackson-serialized `RequestLogEvent`.

- Fields: `method`, `repository` (first decoded path segment, excluding `actuator`/`api`/`ui` —
  `null` for non-repo paths), `path`, `status`, `bytes`, `durationMs`, `principal` (the authenticated
  name from `SecurityContextHolder`, omitted/`null` when anonymous).
- **Bytes without buffering**: wrap the response in a `CountingResponseWrapper` that tallies bytes written
  to the output stream/`PrintWriter` — it does **not** buffer the body (artifacts can be large). Falls
  back to the `Content-Length` header when nothing was streamed.
- **Duration**: `System.nanoTime()` around `filterChain.doFilter`.
- One line per request, machine-parseable JSON, independent of the app's normal console format (does not
  replace or reformat other logs — FR-005, FR-007).

**Rationale**: a filter is the single choke point that sees method/path/status/bytes/duration/principal;
JSON via the already-present Jackson keeps it dependency-free and queryable. Opt-in keeps default console
output human-friendly (clarified).

**Alternatives considered**: Spring Boot 4.1 `logging.structured.format.console` (reformats *all* logs to
JSON — broader than the per-request line the spec asks for, and not independently toggleable); Actuator
`httptrace`/`httpexchanges` (in-memory ring buffer for an endpoint, not a shippable log line);
`CommonsRequestLoggingFilter` (logs request only, no status/bytes/duration, not structured).

## D6 — Operator gating of the management surface

**Decision**: Extend the existing single `SecurityFilterChain` (do **not** add a second chain, and keep
`RepositoryAuthorizationManager` focused on Maven/`/api`). In the security-enabled branch, before
`anyRequest().access(authorizationManager)`:

```
requestMatchers("/actuator/health/liveness", "/actuator/health/readiness").permitAll()
requestMatchers("/actuator/**").hasRole("PUBLISH")
anyRequest().access(authorizationManager)   // unchanged — Maven + /api + /api/cleanup
```

- Probes public; detailed `/actuator/health` (root), `/actuator/prometheus`, `/actuator/metrics`,
  `/actuator/info`, etc. require the global `PUBLISH` role (`401` anon, `403` authenticated-non-publisher)
  — FR-006, SC-004. Reuses the existing `PUBLISH` model and the XHR-aware entry point.
- Security-**disabled** branch is unchanged (`anyRequest().permitAll()`) → probes *and* management open,
  giving local-dev parity (FR-006 "when security disabled, all are open").
- `management.endpoint.health.show-details=always` + `show-components=always`: safe because (a) the
  detailed endpoint is operator-gated at the HTTP layer when security is on, and (b) indicators never emit
  secrets (FR-009). `always` keeps detail visible in local-dev (security off) for parity.

**Rationale**: explicit path matchers are the idiomatic Spring way to special-case the two public probes
while gating the rest; ordering (specific matchers before `anyRequest`) is well-defined. Keeps the
authorization manager and the Maven wire contract untouched.

**Alternatives considered**: a second `SecurityFilterChain` ordered for `/actuator/**` (works but
duplicates entry-point/stateless config and risks chain-ordering subtleties); routing actuator auth
through `RepositoryAuthorizationManager` (overloads a class whose job is repo-path parsing; matchers are
clearer).

## D7 — Dependencies & verification

**Decision**: Add to the version catalog (BOM-managed, no explicit version, mirroring the existing
starters):

- `spring-boot-starter-actuator`
- `micrometer-registry-prometheus`

Then regenerate `gradle/verification-metadata.xml` via
`./gradlew --write-verification-metadata sha256 build` (the established 002/003 procedure). Verification
stays enabled; metadata is **extended**, never disabled (Principle IV). No secrets are introduced.

**Rationale**: both are first-party Spring/Micrometer artifacts resolved through the Spring Boot BOM,
consistent with how Web/Security/Test starters are already declared.
