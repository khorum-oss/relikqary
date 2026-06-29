# Contract: Operational Endpoints & Signals

The operational surface lives under `/actuator/**` — separate from the Maven wire protocol (`/{repo}/**`)
and the browse API (`/api/**`), so it never collides with repository names. All behaviour below is
additive; the Maven/publish/resolve contract is unchanged (FR-007).

## Endpoints

| Endpoint | Method | Auth (security ON) | Auth (security OFF) | Purpose |
|----------|--------|--------------------|--------------------|---------|
| `/actuator/health/liveness` | GET | **public** | public | Liveness probe (process). |
| `/actuator/health/readiness` | GET | **public** | public | Readiness probe (storage-gated). |
| `/actuator/health` | GET | `PUBLISH` | open | Detailed component health (storage, upstreams). |
| `/actuator/prometheus` | GET | `PUBLISH` | open | Prometheus-format metric scrape. |
| `/actuator/metrics` | GET | `PUBLISH` | open | Micrometer metric browse. |
| `/actuator/info` | GET | `PUBLISH` | open | Build/app info. |
| any other `/actuator/**` | * | `PUBLISH` | open | Operator-gated by default. |

`PUBLISH` = the existing global publish role. Unauthenticated → `401` (Maven clients get a Basic
challenge; browser XHR gets a bare `401`). Authenticated without `PUBLISH` → `403`. (FR-006, SC-004.)

## Probe semantics

### Liveness — `GET /actuator/health/liveness`

```json
{ "status": "UP" }
```

`UP` whenever the process is healthy. **Never** gated on storage or upstream reachability — a storage
outage must not trigger a restart (FR-003).

### Readiness — `GET /actuator/health/readiness`

```json
{ "status": "UP" }      // ready: storage reachable/writable
```

- Storage reachable/writable ⇒ `UP`, HTTP `200`.
- Storage unreachable ⇒ `DOWN`, HTTP `503`; recovers to `UP`/`200` when storage returns (FR-002, SC-001).

### Detailed health — `GET /actuator/health` (operator)

```json
{
  "status": "DEGRADED",
  "components": {
    "storage":   { "status": "UP",       "details": { "backend": "s3" } },
    "upstreams": { "status": "DEGRADED",  "details": { "maven-central": { "reachable": false } } },
    "diskSpace": { "status": "UP" },
    "ping":      { "status": "UP" }
  }
}
```

- Overall status is the most severe component status: `DOWN > OUT_OF_SERVICE > DEGRADED > UP > UNKNOWN`.
- `DEGRADED` is HTTP-mapped to `200` — a proxy-upstream outage shows as degraded **without** failing the
  endpoint or the readiness probe (FR-003, SC-005).
- `details` carry **no secrets**: backend label and per-repo `reachable` only — never credentials,
  access keys, endpoints, or passwords (FR-009).

## Metrics — `GET /actuator/prometheus` (operator)

Prometheus exposition format. Present families (FR-004, SC-002):

```text
# HTTP rate / latency / error (Micrometer auto)
http_server_requests_seconds_count{method="GET",status="200",uri="/{repo}/**",...}
http_server_requests_seconds_sum{...}

# Domain counters
relikquary_publish_total{repository="releases",outcome="accepted"}
relikquary_resolve_total{repository="maven-central",outcome="hit"}
relikquary_proxy_cache_total{repository="maven-central",result="miss"}
relikquary_proxy_upstream_total{repository="maven-central",outcome="found"}
relikquary_cleanup_items_removed_total
relikquary_cleanup_bytes_reclaimed_total
relikquary_cleanup_runs_total{dry_run="false"}

# Gauges
relikquary_storage_usage_bytes{backend="filesystem"}
relikquary_storage_objects{backend="filesystem"}
```

(Prometheus client naming: `.` → `_`, counters get a `_total` suffix.) Counters are monotonic; storage
gauges are served from a periodically-refreshed cached total — scraping never triggers a full store walk.

## Structured request log (opt-in)

Enabled with `relikquary.observability.request-log.enabled=true` (default off). One JSON line per request
on the `relikquary.access` logger:

```json
{"method":"GET","repository":"releases","path":"/releases/com/acme/lib/1.0/lib-1.0.jar",
 "status":200,"bytes":20480,"durationMs":7,"principal":"ci"}
```

- `repository` is `null` for non-repo paths; `principal` is omitted/`null` for anonymous requests
  (SC-003). Exactly one line per request. Does not alter the normal application log format (FR-005).

## Configuration

```yaml
relikquary:
  observability:
    request-log:
      enabled: false          # opt-in structured per-request JSON line
    storage-probe-ttl: PT2S   # readiness probe cache
    upstream-health-ttl: PT30S
    storage-usage-refresh: PT5M

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  endpoint:
    health:
      probes:
        enabled: true
      show-details: always    # gated at HTTP layer; never contains secrets
      show-components: always
      group:
        liveness:
          include: livenessState
        readiness:
          include: readinessState,storage
      status:
        order: DOWN,OUT_OF_SERVICE,DEGRADED,UP,UNKNOWN
        http-mapping:
          DEGRADED: 200
```

## Invariants

- Liveness/readiness reachable with **no credentials**; all other operational endpoints operator-gated
  when security is on, open when off (FR-006).
- A proxy-upstream outage never fails liveness or readiness (FR-003).
- No secret (storage/upstream credentials, passwords) appears in any health or metric output (FR-009).
- Health and metrics behave consistently on filesystem and S3 (FR-008).
- Enabling/disabling observability does not change publish/resolve/auth or the Maven contract (FR-007).
