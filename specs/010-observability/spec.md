# Feature Specification: Observability & Operational Readiness

**Feature Branch**: `claude/spec-010-observability` (feature directory `specs/010-observability`)

**Created**: 2026-06-28

**Status**: Draft

**Input**: User description: "Add health/readiness probes, metrics, and structured request logging.
Probes (liveness/readiness) stay open for orchestrators; readiness reflects storage reachability and a
health view surfaces storage + proxy-upstream health. Metrics cover request rates/latency/errors,
publish/resolve counts, proxy cache hit/miss + upstream outcomes, cleanup outcomes, and storage usage, in
a scrapeable format. Structured per-request log line (method, repo, path, status, bytes, duration,
principal). Sensitive operational data requires operator auth; probes do not. Configurable, safe
defaults; Maven contract and publish/resolve/auth unchanged; works on both storage backends."

## Clarifications

### Session 2026-06-28

- Q: How should metrics be exposed for scraping? → A: A dedicated Prometheus-format scrape endpoint
  (introduces a new metrics-exposition dependency, added through the project's dependency-verification
  process).
- Q: Should the per-request structured (JSON) log line be on by default? → A: Opt-in — off by default;
  operators enable it via configuration. Default console output stays human-friendly.
- Q: Beyond liveness/readiness probes, which operational endpoints are public vs operator-gated? → A:
  Only the liveness and readiness probes are public; every other operational endpoint (detailed/component
  health, metrics, info, env, etc.) requires operator authorization when security is enabled.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Health & readiness for orchestrators (Priority: P1)

An operator runs Relikquary under a container orchestrator that needs liveness and readiness probes. The
liveness probe reports whether the process is healthy; the readiness probe reports whether the instance
can actually serve — in particular, whether the configured storage backend is reachable/writable. A
richer health view also surfaces the storage backend and proxy upstream reachability so an operator can
diagnose problems.

**Why this priority**: Without probes the server can't be safely run under Kubernetes/Nomad/etc.; a dead
or storage-detached instance would keep receiving traffic.

**Independent Test**: Query the liveness and readiness probes with no credentials and get a clear
up/ready signal; make storage unavailable and confirm readiness reports not-ready, then restore and
confirm it recovers.

**Acceptance Scenarios**:

1. **Given** a running instance, **When** an orchestrator probes liveness and readiness (no credentials),
   **Then** both return a healthy/ready status.
2. **Given** the configured storage backend becomes unreachable, **When** readiness is probed, **Then** it
   reports not-ready; when storage is restored, readiness recovers.
3. **Given** a proxy repository whose upstream is down, **When** the health view is inspected, **Then** the
   upstream is shown as degraded, but liveness and readiness remain healthy (a transient upstream outage
   must not take the instance out of service).

---

### User Story 2 - Metrics for monitoring (Priority: P1)

An operator points a monitoring system at Relikquary to scrape application metrics: HTTP request rates,
latencies, and error rates; publish and resolve counts; proxy cache hit vs miss and upstream fetch
outcomes; cleanup outcomes (items removed, bytes reclaimed); and storage usage. They build dashboards
and alerts from these.

**Why this priority**: Operating a shared artifact server requires visibility into traffic, cache
effectiveness, cleanup activity, and storage growth.

**Independent Test**: Drive some publishes, resolves, and a proxy cache miss/hit, then scrape metrics and
confirm the corresponding counters/timers are present and increasing.

**Acceptance Scenarios**:

1. **Given** traffic has flowed, **When** metrics are scraped, **Then** HTTP request rate/latency/error
   metrics and publish/resolve counts are present.
2. **Given** a proxy served a cache miss then a cache hit, **When** metrics are scraped, **Then** cache
   hit and miss (and upstream fetch outcome) counters reflect it.
3. **Given** a cleanup run occurred, **When** metrics are scraped, **Then** items-removed and
   bytes-reclaimed are reported, and a storage-usage metric is present.

---

### User Story 3 - Structured request logging (Priority: P2)

An operator ships logs to a central system and queries them. Each request emits one structured,
machine-parseable log line carrying the method, repository, path, status, response size, duration, and
the authenticated principal when present.

**Why this priority**: Structured logs make traffic auditable and debuggable at scale; ad-hoc logs can't
be queried reliably.

**Independent Test**: Make a request and confirm exactly one structured log line is emitted with the
documented fields populated; an authenticated request includes the principal, an anonymous one omits it.

**Acceptance Scenarios**:

1. **Given** a resolve request, **When** it completes, **Then** one structured log line records method,
   repository, path, status, response bytes, and duration.
2. **Given** an authenticated publish, **When** it completes, **Then** the structured log line includes
   the authenticated principal; an anonymous request's line has no principal.

### Edge Cases

- **Probe auth**: liveness and readiness probes never require credentials (orchestrators can't
  authenticate); sensitive operational data must not leak through them.
- **Operator-only data**: every operational endpoint except the liveness/readiness probes (detailed
  health, metrics, info, env, etc.) requires operator authorization when security is enabled (`401`/`403`
  otherwise); when security is disabled, they are open (local-dev parity).
- **Upstream outage isolation**: a proxy upstream being unreachable degrades the health view but never
  fails liveness/readiness.
- **No secret leakage**: storage credentials, upstream credentials, and user passwords are never exposed
  through health or metrics output.
- **Backend parity**: the storage health check adapts to whichever backend is active (filesystem or S3).
- **Disabled observability**: with metrics/structured-logging turned off, the server still serves
  publish/resolve normally.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST expose a liveness probe (process health) and a readiness probe (ability to
  serve), both reachable by an orchestrator without credentials.
- **FR-002**: The readiness probe MUST reflect whether the configured storage backend is reachable/
  writable; when storage is unavailable, readiness MUST report not-ready and recover when it returns.
- **FR-003**: A health view MUST report component health including the active storage backend and proxy
  upstream reachability; an unreachable proxy upstream MUST be reported as degraded but MUST NOT cause
  liveness or readiness to fail.
- **FR-004**: The system MUST expose application metrics on a dedicated Prometheus-format scrape endpoint
  covering: HTTP request rate, latency, and error rate; publish and resolve counts; proxy cache hit vs
  miss and upstream fetch outcomes; cleanup items-removed and bytes-reclaimed; and storage usage.
- **FR-005**: The system MUST be able to emit one structured, machine-parseable (JSON) log line per
  request including the method, repository, path, status, response size, duration, and the authenticated
  principal when present. This structured request log is OFF by default and enabled via configuration;
  when off, normal application logging is unchanged.
- **FR-006**: Every operational endpoint other than the liveness and readiness probes (detailed/component
  health, metrics, info, env, etc.) MUST require operator authorization when security is enabled (`401`
  unauthenticated, `403` authenticated-without-role); the liveness and readiness probes MUST remain
  unauthenticated; when security is disabled, all are open.
- **FR-007**: Observability features MUST be configurable with safe defaults, and enabling/disabling them
  MUST NOT change publish/resolve/auth behavior or the Maven client contract.
- **FR-008**: Health and metrics MUST behave consistently across the filesystem and S3 storage backends
  (the storage health check adapts to the active backend).
- **FR-009**: Health and metrics output MUST NOT expose secrets (storage/upstream credentials, user
  passwords).

### Key Entities

- **Probe**: a liveness or readiness signal consumed by an orchestrator; unauthenticated.
- **Health Component**: a named health contributor (e.g. storage backend, proxy upstream) with a status.
- **Metric**: a named, scrapeable measurement (counter/timer/gauge) — request rates, publish/resolve
  counts, cache hit/miss, cleanup outcomes, storage usage.
- **Request Log Event**: the structured per-request record (method, repository, path, status, bytes,
  duration, principal).

## Success Criteria *(mandatory)*

- **SC-001**: An orchestrator probes liveness and readiness without credentials; readiness flips to
  not-ready when storage is unavailable and recovers when storage returns.
- **SC-002**: A monitoring system scrapes metrics covering request rate/latency/error, publish/resolve
  counts, proxy cache hit/miss, cleanup outcomes, and storage usage.
- **SC-003**: Each request emits exactly one structured log line with the documented fields; the principal
  appears only when the request is authenticated.
- **SC-004**: With security enabled, metrics and detailed health require operator credentials (`401`/`403`
  otherwise) while probes stay open; with security disabled all are reachable.
- **SC-005**: A proxy upstream outage shows as degraded in the health view without marking the instance
  not-live or not-ready.
- **SC-006**: Enabling observability does not change publish/resolve/auth behavior or the Maven client
  contract (existing round-trips still pass).

## Assumptions

- **Operational surface**: operational endpoints live under a dedicated management path, separate from the
  Maven protocol (`/{repo}/**`) and the `/api` browse surface, so they don't collide with repository
  names or the wire protocol.
- **Metrics format**: metrics are exposed in Prometheus scrape (pull) format on a dedicated endpoint for a
  monitoring system to collect (resolved in clarification; introduces a metrics-exposition dependency
  added through the project's dependency-verification process).
- **Structured logging**: the per-request log is emitted to standard output in a machine-parseable (JSON)
  format; it is OFF by default and turned on via configuration (resolved in clarification).
- **Probe semantics**: liveness = the process is healthy; readiness = the instance can serve (storage
  reachable). Proxy upstream reachability is informational in the health view, not a readiness gate.
- **Authorization**: operator authorization for sensitive operational data reuses the existing `PUBLISH`
  role and auth model; probes are always unauthenticated.
- **Dependencies**: enabling metrics/health may introduce a new dependency; if so it is added through the
  project's dependency-verification process (never by disabling verification).
- **Health-check cost**: the storage readiness check is lightweight (a cheap reachability/writability
  check) and does not materially affect request throughput.
