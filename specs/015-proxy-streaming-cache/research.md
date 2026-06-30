# Research: Streaming Proxy Cache (Tee)

Phase 0 design decisions. No `NEEDS CLARIFICATION` remained from the spec; the items below resolve
the *how* for planning.

## D1. Tee mechanism: read-driven mirror

- **Decision**: Wrap the upstream `InputStream` in a `TeeInputStream` that, on each `read(...)`,
  writes the same bytes to a pending cache sink. The resolver returns the tee stream as the
  `StoredArtifact`; the controller streams it to the client. As the client (Spring's
  `InputStreamResource` copy) reads through, bytes are mirrored to the cache. Promotion is driven by
  how the read terminates: underlying EOF reached → `commit()`; closed before EOF, or a read threw →
  `abort()`.
- **Rationale**: A single consumer (the client copy) drives both delivery and caching, so there is no
  second thread, no buffering of the whole artifact, and the overlap (FR-001) is automatic. The
  terminal condition cleanly distinguishes a complete transfer from a truncated one (FR-002/003/004).
- **Alternatives considered**:
  - *Background-thread fetch + cache, serve from a pipe*: more moving parts, thread management, and a
    second failure surface; no benefit over read-driven.
  - *Fetch fully into memory then serve+write*: defeats the streaming goal and risks OOM on large
    artifacts.
  - *Keep write-then-openRead, just drop the re-read by returning the temp file handle*: still fully
    pre-buffers before the first client byte (no overlap); fails FR-001.

## D2. Backend-agnostic pending write: `ArtifactWrite` (commit/abort)

- **Decision**: Add `ArtifactStorage.openWrite(key): ArtifactWrite`. `ArtifactWrite` is `Closeable`
  with `val sink: OutputStream`, `fun commit(): Long`, `fun abort()`. Filesystem implements it with
  the existing temp-file-in-target-dir + `ATOMIC_MOVE`-on-commit (delete temp on abort). S3 writes
  the sink to a temp file and, on commit, `putObject`s it (delete temp on abort) — mirroring the
  existing `S3ArtifactStorage.write` buffer-to-temp approach.
- **Rationale**: Keeps the tee uniform across backends and preserves the "only a complete transfer
  becomes a cache entry" guarantee in the place that already owns atomicity (the storage layer).
  Reuses each backend's proven durability pattern rather than inventing a new one.
- **Alternatives considered**:
  - *Stream straight to S3 via multipart upload as bytes arrive*: more complex, needs part buffering
    and abort handling; the temp-file approach matches current code and is sufficient (the **client**
    is already unblocked by the tee; the cache commit happening at end-of-stream is fine).
  - *Reuse `write(key, InputStream)`*: it fully consumes the stream itself, which is incompatible
    with the read-driven tee (the client must be the consumer).

## D3. Unknown content length

- **Decision**: Carry the upstream `Content-Length` through to `StoredArtifact` when present
  (`UpstreamResponse.Found.contentLength`). When absent, represent the size as "unknown" and have the
  controller omit the `Content-Length` header so the response is chunked.
- **Rationale**: Maven Central and the Plugin Portal normally send `Content-Length`, so the common
  path keeps a known length; chunked is a standard, Maven/Gradle-compatible fallback. Avoids buffering
  just to learn the length (which would defeat streaming).
- **Implementation note**: `StoredArtifact.sizeBytes` becomes nullable-or-sentinel; the controller
  sets `contentLength(...)` only when known. Cache reads (filesystem/S3) always know the size, so hits
  are unaffected.

## D4. Promotion timing & failure semantics

- **Decision**: `commit()` happens exactly once, when the tee observes underlying-stream EOF during
  `close()` (full successful transfer). Any other close (client disconnect before EOF) or a read-time
  `IOException` (upstream truncation/reset) calls `abort()`. `commit`/`abort` are idempotent and
  mutually exclusive; `close()` is safe to call multiple times.
- **Rationale**: Directly encodes FR-002/003/004 — a partial transfer never promotes. The temp file
  is always cleaned up (commit moves it; abort deletes it; an unexpected path deletes on close).
- **Edge**: A legitimately empty (length-0) upstream 200 reaches EOF immediately → commits an empty
  entry (a valid artifact), distinct from a truncated transfer that errors before EOF.

## D5. Metadata pass-through unchanged

- **Decision**: `proxy()`'s `PathKind.METADATA` branch still calls `passThrough()` (fetch fresh,
  never cache). Only the non-metadata cache-miss branch changes to the tee.
- **Rationale**: FR-008; `maven-metadata.xml` must stay fresh from upstream.

## D6. Concurrency stance (scope boundary)

- **Decision**: Two concurrent first requests for the same uncached artifact each run their own tee
  to their own temp file; both commit (atomic move ⇒ last-writer-wins, both files are complete and
  byte-identical). No coalescing.
- **Rationale**: Matches today's behavior and keeps the change small; integrity holds because each
  promotion is a complete copy. Coalescing is listed Out of Scope in the spec.

## D7. Test harness for streaming/failure

- **Decision**: Extend the existing `StubUpstream` (`com.sun.net.httpserver.HttpServer`) with modes:
  (a) slow-drip body (flush a prefix, pause, flush the rest) to assert the client gets first bytes
  before completion; (b) truncate-midway (declare a `Content-Length` then close early) to assert
  nothing is cached; (c) normal. Client disconnect is simulated by closing the client connection /
  aborting the read mid-body. Assertions are behavioral (cache contents, byte identity via checksum),
  avoiding wall-clock timing thresholds that flake.
- **Rationale**: Reuses the established proxy test pattern (feature 006); satisfies Principle II with
  real HTTP round-trips and no mocked storage. S3 path uses Testcontainers MinIO.

## Open questions

None. All resolved above.
