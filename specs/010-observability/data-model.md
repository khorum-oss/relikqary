# Data Model: Observability & Operational Readiness

Feature 010 adds operational *signals*, not persistent domain data. The "entities" below are
configuration, in-memory signal carriers, and the metric/health/log shapes the system exposes. Nothing
here is stored in or read from the artifact store except the cheap storage probe and the cached usage
total.

## Configuration

### `ObservabilityProperties` — `@ConfigurationProperties("relikquary.observability")`

| Field | Type | Default | Meaning |
|-------|------|---------|---------|
| `requestLog.enabled` | `Boolean` | `false` | Opt-in structured per-request JSON log (FR-005). |
| `requestLog.includeQueryString` | `Boolean` | `false` | Append the query string to the logged `path`. |
| `storageProbeTtl` | `Duration` | `PT2S` | TTL cache for the storage readiness probe (D2). |
| `upstreamHealthTtl` | `Duration` | `PT30S` | TTL cache for proxy-upstream reachability checks (D3). |
| `storageUsageRefresh` | `Duration` | `PT5M` | Refresh interval for the cached storage-usage gauges (D4). |

Registered in `RelikquaryApplication` `@EnableConfigurationProperties`. All fields optional with safe
defaults (FR-007). The standard `management.*` keys (exposure, health groups, status mapping) live in
`application.yml` and are not duplicated here.

## Storage probe

### `StorageProbe` (returned by `ArtifactStorage.probe()`)

| Field | Type | Meaning |
|-------|------|---------|
| `healthy` | `Boolean` | Backend reachable (and, for filesystem, writable). |
| `backend` | `String` | `"filesystem"` or `"s3"` — the active backend label. |
| `detail` | `String?` | Short, **non-secret** reason when `healthy=false` (else `null`). |

- **Filesystem** `probe()`: `healthy = isDirectory(root) && isWritable(root)`; no file is written.
- **S3** `probe()`: `healthy` ⇐ a `headBucket` succeeds; no object is written.
- Invariant (FR-009): `detail`/`backend` never contain credentials, keys, endpoints, or bucket secrets.

## Health (Actuator)

### Health groups

| Group | Endpoint | Members | DOWN ⇒ |
|-------|----------|---------|--------|
| `liveness` | `/actuator/health/liveness` | `livenessState` | process unhealthy (rare) |
| `readiness` | `/actuator/health/readiness` | `readinessState`, `storage` | `503` not-ready (FR-002) |

### Health indicators

- **`StorageHealthIndicator`** (id `storage`): `UP` when `probe().healthy`, else `DOWN` with the
  non-secret `detail`. Member of the `readiness` group.
- **`UpstreamHealthIndicator`** (id `upstreams`): aggregates per-PROXY-repo reachability. `UP` when all
  reachable; **`DEGRADED`** (custom status) when one or more are unreachable. **Not** in `liveness`/
  `readiness`. Detail: `{ repoName: { reachable: Boolean } }` — no upstream credentials (FR-009).

### Custom status

`DEGRADED` — severity ordered `DOWN > OUT_OF_SERVICE > DEGRADED > UP > UNKNOWN`; HTTP-mapped to `200` so a
proxy-upstream outage shows as degraded on `/actuator/health` without returning `503` (FR-003, SC-005).

## Metrics (Micrometer → Prometheus)

Exposed at `/actuator/prometheus` (operator-gated). Auto-provided HTTP metrics plus custom meters:

| Meter | Type | Tags | Source seam |
|-------|------|------|-------------|
| `http.server.requests` | Timer (auto) | `method`, `uri`, `status`, `outcome` | Micrometer auto-config (FR-004 HTTP) |
| `relikquary.publish` | Counter | `repository`, `outcome` | `RepositoryController.publish` |
| `relikquary.resolve` | Counter | `repository`, `outcome` | `RepositoryController.resolve` |
| `relikquary.proxy.cache` | Counter | `repository`, `result`=hit\|miss | `RepositoryResolver.proxy` |
| `relikquary.proxy.upstream` | Counter | `repository`, `outcome`=found\|not_found\|error | `RepositoryResolver.proxy` |
| `relikquary.cleanup.items.removed` | Counter | — | `CleanupService.run` |
| `relikquary.cleanup.bytes.reclaimed` | Counter | — | `CleanupService.run` |
| `relikquary.cleanup.runs` | Counter | `dry_run` | `CleanupService.run` |
| `relikquary.storage.usage.bytes` | Gauge | `backend` | cached `walk("")` total (refreshed) |
| `relikquary.storage.objects` | Gauge | `backend` | cached object count |

`repository` tag cardinality is bounded by the configured repository list. Counters are monotonic; usage
is a gauge served from a cached value (never a per-scrape `walk`).

## Logging

### `RequestLogEvent` (serialized to one JSON line on the `relikquary.access` logger)

| Field | Type | Meaning |
|-------|------|---------|
| `method` | `String` | HTTP method. |
| `repository` | `String?` | First decoded path segment (excl. `actuator`/`api`/`ui`); `null` if none. |
| `path` | `String` | Request path (optionally with query string). |
| `status` | `Int` | Response status code. |
| `bytes` | `Long` | Response body bytes written (counted, not buffered). |
| `durationMs` | `Long` | Wall-clock handling time. |
| `principal` | `String?` | Authenticated username; `null`/omitted when anonymous. |

Emitted only when `requestLog.enabled=true`; exactly one line per request (SC-003). Independent of the
normal application log format.

## Relationships & lifecycle

- `ObservabilityProperties` parameterizes the probe/health/usage caches and the request-log toggle.
- `ArtifactStorage.probe()` ← `StorageHealthIndicator` ← `readiness` group ← `/actuator/health/readiness`.
- Seam recorders (`RepositoryMetrics`, `CleanupMetricsRecorder`, `StorageUsageMetrics`) feed the single
  `MeterRegistry` → `/actuator/prometheus`.
- `RequestLoggingFilter` (when enabled) wraps every request → emits one `RequestLogEvent`.
- No new persistent state; the only store interaction is the side-effect-free probe and the cached usage
  walk. Faithful storage (Principle IV) is preserved.
