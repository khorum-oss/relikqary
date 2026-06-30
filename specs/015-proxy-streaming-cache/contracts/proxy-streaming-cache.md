# Contract: Proxy Streaming Cache (Tee)

Two internal contracts plus the unchanged external HTTP contract.

## 1. Storage: pending write (`ArtifactStorage.openWrite`)

```
openWrite(key: String): ArtifactWrite
```

| Guarantee | Requirement |
|-----------|-------------|
| Isolation | Bytes written to `ArtifactWrite.sink` are invisible at `key` until `commit()`. |
| Atomic promotion | After `commit()`, `openRead(key)`/`exists(key)` observe the complete bytes; partial bytes are never observable. |
| Abort safety | After `abort()` (or `close()` without commit), no cache entry exists at `key` and no temp residue remains. |
| Byte fidelity | The committed bytes equal the bytes written to `sink`, exactly (constitution IV). |
| Backends | Implemented by both `FilesystemArtifactStorage` and `S3ArtifactStorage`. |

## 2. Resolver: proxy cache-miss behavior (`RepositoryResolver.proxy`)

| # | Given | When | Then |
|---|-------|------|------|
| R1 | cache miss, non-metadata path | upstream returns 200 and the client reads to completion | client receives byte-identical artifact; a cache entry is created; **no** post-write re-read is used to produce the response (FR-006) |
| R2 | cache miss | upstream is streaming a large body | client receives initial bytes before the upstream transfer completes (FR-001) — no full server-side pre-buffer |
| R3 | cache miss | client disconnects mid-transfer | no cache entry exists for the artifact (FR-003) |
| R4 | cache miss | upstream truncates/resets mid-body | no cache entry; a later request re-fetches (FR-004) |
| R5 | cache hit | request | served from storage; upstream not contacted (FR-007) |
| R6 | metadata path (`maven-metadata.xml`) | request | served fresh from upstream; never cached (FR-008) |
| R7 | upstream 404/410 | request | `Resolution.Miss` (HTTP 404); nothing cached (FR-009) |
| R8 | upstream error/unreachable/unexpected status | request | `Resolution.UpstreamError` (HTTP 502); nothing cached (FR-009) |

## 3. HTTP contract (UNCHANGED — FR-010)

| Aspect | Behavior |
|--------|----------|
| Status codes | Hit → 200; Miss → 404; UpstreamError → 502 (unchanged). |
| Body bytes | Identical to today / to the upstream. |
| `Content-Length` | Present and correct when the size is known (cache hits; upstream sent a length). **May be absent (chunked)** for a streamed miss when the upstream omits `Content-Length` — the only outward-visible difference, standard and client-compatible. |
| Group semantics | First-match across members and per-member READ authorization unchanged. |

## Test obligations (Principle II — written failing first)

- **R1/R2** — `ProxyStreamingCacheIT`: cold-cache resolve via a real `@SpringBootTest` + `StubUpstream`;
  assert byte-identical delivery (checksum) and cache population; with a slow-drip upstream, assert
  the client observes initial bytes before transfer completion.
- **R3/R4** — `ProxyStreamingFailureIT`: client disconnect and upstream mid-body truncation each leave
  the cache with **zero** entries for the artifact; a subsequent request succeeds.
- **R1 (S3)** — exercise the S3 storage path via Testcontainers MinIO (`DynamicPropertySource`).
- **Unit** — `TeeInputStreamTest`: EOF ⇒ commit; close-before-EOF ⇒ abort; read `IOException` ⇒ abort;
  double-close is safe.
- **Regression** — existing `ProxyResolveTest` / `ProxyRoundTripTest` / `GroupResolveTest` /
  `ProxyEvictionTest` stay green (hit/miss/error and group semantics unchanged).
