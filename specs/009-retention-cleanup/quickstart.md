# Quickstart: Retention & Cleanup

Validates feature 009. See [contracts/cleanup-api.md](contracts/cleanup-api.md) and
[data-model.md](data-model.md) for the full contract.

## Prerequisites

- JDK 21; `./gradlew :backend:bootJar`.
- A config with a snapshot repo (keep-last-3) and a proxy with a small cache budget, e.g.:

```yaml
relikquary:
  cleanup:
    enabled: true
    interval: PT1H
  security:
    enabled: false        # demo
  repositories:
    - name: snapshots
      type: snapshot
      retention:
        snapshot: { keepLast: 3 }
    - name: maven-central
      kind: proxy
      remoteUrl: https://repo1.maven.org/maven2
      retention:
        cache: { maxSize: 50MB }
```

```bash
java -jar backend/build/libs/backend.jar --spring.config.location=file:that-config.yml \
  --relikquary.storage.filesystem.root="$(mktemp -d)"
```

## Scenario 1 — Snapshot retention

```bash
# Seed 5 timestamped builds of one snapshot artifact.
for n in 1 2 3 4 5; do
  ts=$(printf '2026010%d.120000-%d' "$n" "$n")
  base="http://localhost:8080/snapshots/com/acme/lib/1.0.0-SNAPSHOT/lib-1.0.0-$ts"
  printf 'jar' | curl -sf -X PUT --data-binary @- "$base.jar"
  printf 'pom' | curl -sf -X PUT --data-binary @- "$base.pom"
done

# Dry-run shows what would go; then a real run keeps the 3 newest builds.
curl -sf -X POST 'http://localhost:8080/api/cleanup?dryRun=true' | jq .
curl -sf -X POST 'http://localhost:8080/api/cleanup' | jq .
```

Expected: the dry-run reports the 2 oldest builds without deleting; the real run removes them, the 3
newest builds remain, and the artifact still resolves.

## Scenario 2 — Proxy cache eviction

Resolve several artifacts through `maven-central` to populate the cache beyond the `maxSize` budget, run
`POST /api/cleanup`, and confirm the oldest cached artifacts are evicted until within budget. Request an
evicted artifact again and confirm it is re-fetched and served.

## Scenario 3 — Safety

- A release repository is unchanged by any run.
- A dry-run (`dryRun=true`) reports a selection but leaves storage byte-for-byte unchanged.
- With `relikquary.security.enabled=true`, `POST /api/cleanup` requires the `PUBLISH` role (`401`/`403`
  otherwise).

## Automated verification

```bash
./gradlew build      # backend: detekt + Kover + unit/integration (retention, eviction, dry-run, auth);
                     # storage walk on filesystem + S3; existing suites unchanged
```

Expected: green; cleanup runs through the real HTTP + storage boundary, both backends exercised.
