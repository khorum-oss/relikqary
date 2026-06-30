# Quickstart: Streaming Proxy Cache (Tee)

Validation scenarios proving the feature works end-to-end. Details live in
[contracts/proxy-streaming-cache.md](./contracts/proxy-streaming-cache.md) and
[data-model.md](./data-model.md).

## Prerequisites

- `./gradlew :backend:test` runs the JVM test suite.
- A proxy repo configured with an upstream (the tests use the in-process `StubUpstream`; manual
  checks can use the `maven-central` proxy or `public` group against the running server).

## Automated validation (authoritative)

```sh
./gradlew :backend:test --tests '*ProxyStreamingCacheIT' \
                        --tests '*ProxyStreamingFailureIT' \
                        --tests '*TeeInputStreamTest'
# plus regression:
./gradlew :backend:test --tests '*ProxyResolveTest' --tests '*GroupResolveTest' \
                        --tests '*ProxyEvictionTest' --tests '*ProxyRoundTripTest'
```

Expected:
- **Cold-cache resolve (R1/R2)**: artifact returned to the client is byte-identical to the upstream
  (checksum match); a cache entry now exists; with a slow-drip upstream, the client receives initial
  bytes before the upstream transfer completes.
- **Client disconnect (R3)**: after aborting the read mid-body, the cache holds **no** entry for the
  artifact.
- **Upstream truncation (R4)**: after the upstream closes mid-body, the cache holds **no** entry; a
  re-request succeeds.
- **Unit (TeeInputStream)**: EOF ⇒ commit; early close ⇒ abort; read error ⇒ abort; double-close safe.
- **S3 path**: the same R1 behavior holds against Testcontainers MinIO.
- **Regression**: hit/miss/error status codes and group first-match semantics unchanged.

## Manual smoke check (against a running server)

```sh
# cold cache: remove any cached copy, then resolve through the proxy/group
A="org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.jar"
curl -s "http://localhost:8081/public/$A" -o /tmp/a.jar -w "HTTP %{http_code}  TTFB %{time_starttransfer}s  total %{time_total}s\n"
shasum -a 256 /tmp/a.jar      # compare to Maven Central's published checksum (must match)

# second resolve is a cache hit (no upstream request — confirm in the request log)
curl -s "http://localhost:8081/public/$A" -o /tmp/a2.jar -w "HTTP %{http_code}  total %{time_total}s\n"
shasum -a 256 /tmp/a2.jar     # identical to /tmp/a.jar
```

Expected: first call returns 200 with `time_starttransfer` close to the upstream's TTFB (not the full
download time); the cached copy and a re-fetch from upstream are byte-identical; the second call is
served from cache (visible as no upstream `GET` for that path, e.g. via the request log / cache
metrics).

## Done When

- All listed tests pass (new + regression).
- A cold-cache artifact's checksum matches the upstream's published checksum.
- Disconnect and truncation leave zero cache entries for the failed artifact.
