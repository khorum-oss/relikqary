# Quickstart: Proxy & Group Repositories

Validates feature 006 end-to-end. See [contracts/proxy-group.md](contracts/proxy-group.md) and
[data-model.md](data-model.md) for the full contract.

## Prerequisites

- JDK 21, the repo checked out, `./gradlew` available.
- Default config ships a `maven-central` proxy and a `public` group (over `releases` + `maven-central`).

## Build & run

```bash
./gradlew :backend:bootJar
java -jar backend/build/libs/backend.jar --relikquary.security.enabled=false
```

## Scenario 1 — Resolve through the proxy (cache miss → cache hit)

```bash
# Cold cache: fetched from upstream, stored locally, returned (200)
curl -sf -o /tmp/g.jar \
  http://localhost:8080/maven-central/com/google/guava/guava/33.0.0-jre/guava-33.0.0-jre.jar
# Second request is served from the local cache (no upstream contact)
curl -sf -o /tmp/g2.jar \
  http://localhost:8080/maven-central/com/google/guava/guava/33.0.0-jre/guava-33.0.0-jre.jar
cmp /tmp/g.jar /tmp/g2.jar && echo "byte-for-byte identical"
```

Expected: both `200`; the artifact appears under the proxy's storage prefix
(`<store>/maven-central/com/google/guava/...`). A missing upstream coordinate returns `404`.

## Scenario 2 — One group URL for first-party + proxied deps

```bash
# Publish a first-party artifact to the hosted 'releases' repo (member of 'public')
BASE=http://localhost:8080/releases/com/example/widget/1.0.0/widget-1.0.0
printf 'jar'        | curl -sf -X PUT --data-binary @- "$BASE.jar"
printf '<project/>' | curl -sf -X PUT --data-binary @- "$BASE.pom"

# Resolve BOTH through the single 'public' group URL
curl -sf -o /dev/null -w "first-party  %{http_code}\n" \
  http://localhost:8080/public/com/example/widget/1.0.0/widget-1.0.0.jar
curl -sf -o /dev/null -w "proxied dep  %{http_code}\n" \
  http://localhost:8080/public/com/google/guava/guava/33.0.0-jre/guava-33.0.0-jre.jar
```

Expected: `200` for both (first-party from the hosted member, Guava via the proxy member).

## Scenario 3 — Read-only enforcement

```bash
curl -s -o /dev/null -w "proxy PUT %{http_code}\n" -X PUT --data 'x' \
  http://localhost:8080/maven-central/com/example/x/1.0/x-1.0.jar    # 405
curl -s -o /dev/null -w "group PUT %{http_code}\n" -X PUT --data 'x' \
  http://localhost:8080/public/com/example/x/1.0/x-1.0.jar           # 405
```

## Scenario 4 — Real client round-trip (the acceptance bar)

A Gradle/Maven build configured with the **group** (or proxy) URL as its only repository resolves an
external dependency through Relikquary; the dependency is then cached locally and re-resolves with the
upstream offline. This is exercised by the automated round-trip test (local stub upstream by default;
real-Maven-Central variant guarded to auto-skip offline).

## Automated verification

```bash
./gradlew build      # backend: detekt + Kover + unit + integration (incl. proxy/group round-trips)
```

Expected: green; proxy round-trip runs against the local stub upstream, the real-Central test skips when
offline, and the existing hosted publish/resolve + DELETE-auth suites still pass unchanged.
