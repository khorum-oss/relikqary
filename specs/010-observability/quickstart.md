# Quickstart: Observability & Operational Readiness

Validates feature 010. See [contracts/observability.md](contracts/observability.md) and
[data-model.md](data-model.md) for the full contract.

## Prerequisites

- JDK 21; `./gradlew :backend:bootJar`.
- A config enabling auth (to demonstrate operator gating) and the opt-in request log, e.g.:

```yaml
relikquary:
  observability:
    request-log:
      enabled: true
  security:
    enabled: true
    users:
      - { username: ci, password: "{noop}secret", roles: [PUBLISH] }
      - { username: reader, password: "{noop}secret", roles: [] }
  repositories:
    - name: releases
      type: release
    - name: maven-central
      kind: proxy
      remoteUrl: https://repo1.maven.org/maven2
```

```bash
java -jar backend/build/libs/backend.jar --spring.config.location=file:that-config.yml \
  --relikquary.storage.filesystem.root="$(mktemp -d)"
```

## Scenario 1 — Probes (no credentials)

```bash
curl -sf http://localhost:8080/actuator/health/liveness    # {"status":"UP"}
curl -sf http://localhost:8080/actuator/health/readiness   # {"status":"UP"}
```

Both return `200` without credentials. Now make storage unavailable (point the root at a non-writable
path or stop the S3 backend) and re-probe readiness:

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/actuator/health/readiness   # 503
```

Restore storage → readiness returns `200` again. Liveness stays `200` throughout.

## Scenario 2 — Operator-gated metrics & detailed health

```bash
# Unauthenticated → 401; reader (no PUBLISH) → 403; ci (PUBLISH) → 200.
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/actuator/prometheus            # 401
curl -s -o /dev/null -w '%{http_code}\n' -u reader:secret http://localhost:8080/actuator/prometheus  # 403
curl -sf -u ci:secret http://localhost:8080/actuator/prometheus | grep relikquary_            # 200 + meters
```

Drive some traffic first (publish to `/releases`, resolve through `/maven-central`, run
`POST /api/cleanup`) and confirm `relikquary_publish_total`, `relikquary_resolve_total`,
`relikquary_proxy_cache_total`, `relikquary_cleanup_*`, and `relikquary_storage_usage_bytes` appear and
increase.

## Scenario 3 — Upstream degraded, instance stays ready

Point `maven-central` at an unreachable upstream, then:

```bash
curl -sf -u ci:secret http://localhost:8080/actuator/health | jq '.status, .components.upstreams.status'
# "DEGRADED" "DEGRADED"   (HTTP 200)
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/actuator/health/readiness   # 200 (still ready)
```

The upstream outage shows as degraded in the detailed view but never fails liveness/readiness.

## Scenario 4 — Structured request log

With `request-log.enabled=true`, each request emits one JSON line on the `relikquary.access` logger:

```json
{"method":"GET","repository":"releases","path":"/releases/com/acme/lib/1.0/lib-1.0.jar","status":200,"bytes":20480,"durationMs":7,"principal":"ci"}
```

An anonymous request omits `principal`. With `request-log.enabled=false` (default) no such line is
emitted and normal logging is unchanged.

## Scenario 5 — No secrets leaked

```bash
curl -sf -u ci:secret http://localhost:8080/actuator/health | grep -iE 'secret|password|accessKey' && echo LEAK || echo clean
# clean
```

## Automated verification

```bash
./gradlew build      # backend: detekt + Kover + unit/integration (probes, metrics scrape, operational
                     # auth matrix, upstream-degraded, request-log) + S3 storage-probe parity; existing
                     # publish/resolve round-trips unchanged; verification-metadata.xml extended for the
                     # two new dependencies
```

Expected: green. Probes/metrics are scraped over real HTTP; storage-down readiness uses a real broken
backend; the S3 probe runs against the s3mock boundary.
