# Feature Specification: Streaming Proxy Cache (Tee)

**Feature Branch**: `015-proxy-streaming-cache`

**Created**: 2026-06-29

**Status**: Implemented (filesystem + S3 verified; full `:backend:build` / Kover pending CI)

**Implementation note**: During build, FR-002/FR-004 were hardened beyond the original tee design: the
streaming wrapper additionally verifies the mirrored byte count equals the upstream's declared
Content-Length before committing, because `java.net.http.HttpClient` can surface a truncated
fixed-length body as a clean EOF rather than an error. This strengthens "never cache a partial
artifact" without changing any external behavior.

**Input**: User description: "Stream proxy-repository upstream responses to the requesting client while caching them (a tee), to speed up cold-cache dependency/plugin downloads. Today a proxy cache miss fully downloads the artifact, writes it to disk, then re-reads it from disk before sending the client a single byte — fully serialized, with a redundant disk read. Stream upstream bytes to the client while writing the cache simultaneously, and serve from the fetched bytes rather than a re-read. Preserve the temp-file + atomic-move safety so a client disconnect or mid-stream upstream failure never promotes a truncated artifact into the cache. maven-metadata.xml pass-through is unchanged."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Faster cold-cache resolution through a proxy (Priority: P1)

A build (Gradle or Maven) resolves a dependency or plugin through a Relikquary proxy (or a group
containing one) for the first time, when the artifact is not yet cached. The consumer begins
receiving the artifact bytes as soon as Relikquary starts receiving them from the upstream, instead
of waiting for Relikquary to download the entire artifact and persist it first.

**Why this priority**: This is the whole point of the feature — cold-cache builds are the slow path
users feel, and overlapping the consumer's download with the upstream fetch is the dominant win.

**Independent Test**: Point a build at a proxy/group repo with an empty cache and resolve an
artifact; observe that the consumer receives the complete, byte-correct artifact and that
time-to-first-byte tracks the upstream's, not the full upstream download time.

**Acceptance Scenarios**:

1. **Given** a proxy repo whose cache does not contain artifact X, **When** a client requests X and
   the upstream returns it, **Then** the client receives the byte-for-byte-identical artifact and a
   cache entry for X is created.
2. **Given** the same request, **When** the upstream begins streaming the body, **Then** the client
   begins receiving bytes before the upstream transfer has fully completed (the download is not
   fully buffered server-side before the first byte reaches the client).
3. **Given** artifact X was just resolved through the proxy, **When** the same artifact is requested
   again, **Then** it is served from the local cache without contacting the upstream.

---

### User Story 2 - Cache integrity is never weakened (Priority: P1)

An operator relies on the proxy cache containing only complete, faithful copies of upstream
artifacts. A failure during a streamed transfer must never leave a truncated or corrupt artifact
that a later request would serve as if valid.

**Why this priority**: Relikquary's core guarantee is faithful, byte-for-byte storage (constitution
Principle IV). A speed optimization that could cache half a jar would be worse than the slowness it
fixes — a corrupt cached artifact poisons every downstream build until manually purged.

**Independent Test**: Simulate a client disconnect and an upstream failure partway through a
streamed transfer; afterward, confirm the cache contains no entry for that artifact and the next
request re-fetches cleanly.

**Acceptance Scenarios**:

1. **Given** a streamed proxy transfer of artifact X, **When** the client disconnects before the
   transfer completes, **Then** no cache entry for X exists afterward.
2. **Given** a streamed proxy transfer of artifact X, **When** the upstream connection fails or
   truncates mid-body, **Then** no cache entry for X exists afterward and a subsequent request
   re-attempts the fetch.
3. **Given** a completed streamed transfer of artifact X, **When** the cache entry is later read,
   **Then** its bytes are identical to what the upstream served (same checksum), satisfying client
   dependency verification.

---

### User Story 3 - No redundant disk read on a miss (Priority: P2)

When an artifact is fetched and cached on a miss, the response served to the client does not require
re-reading the freshly written file back from storage.

**Why this priority**: Removes wasted I/O per cold-cache artifact. Smaller than the streaming win
but directly addresses the redundant read in the current path; valuable at scale (a full build can
miss on hundreds of artifacts).

**Independent Test**: Resolve an uncached artifact through the proxy and confirm the artifact is
both served to the client and present in the cache, without a separate post-write re-read of the
same artifact to produce the response.

**Acceptance Scenarios**:

1. **Given** a cache miss for artifact X, **When** X is fetched and cached, **Then** the client is
   served the artifact without an additional full re-read of the just-written cache file.

---

### Edge Cases

- **Upstream omits content length**: the artifact is still streamed to the client (length unknown
  up front) and cached on successful completion.
- **Client disconnects after partial delivery**: the partial cache file is discarded; no cache
  entry is promoted.
- **Upstream returns 404/410**: behaves as today — a miss (HTTP 404 to the client); nothing cached.
- **Upstream returns an error / is unreachable**: behaves as today — HTTP 502; nothing cached.
- **Concurrent first requests for the same uncached artifact**: each request is served correctly and
  the final cache entry is a complete, faithful copy; coordination/de-duplication of concurrent
  upstream fetches is out of scope (see Assumptions).
- **`maven-metadata.xml` (and other metadata) requests**: unchanged — always served fresh from
  upstream, never cached.
- **Zero-byte artifact**: a legitimately empty (length 0) successful upstream response is delivered
  and cached as an empty entry; this is distinct from a truncated transfer (which fails before the
  upstream signals completion).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: On a proxy cache miss for a cacheable artifact, the system MUST stream the upstream
  response body to the requesting client while writing the same bytes to the cache, so the client
  begins receiving data before the full upstream transfer completes.
- **FR-002**: The system MUST promote a streamed transfer into a durable cache entry only after the
  artifact has been received in full and successfully; an incomplete transfer MUST NOT become a
  cache entry.
- **FR-003**: A client disconnect during a streamed transfer MUST NOT result in a cached artifact.
- **FR-004**: An upstream failure or truncation during a streamed transfer MUST NOT result in a
  cached artifact, and a subsequent request MUST re-attempt the fetch.
- **FR-005**: A cached artifact produced by streaming MUST be byte-for-byte identical to the bytes
  the upstream served, preserving checksum integrity for client dependency verification.
- **FR-006**: On a cache miss, the system MUST serve the client from the bytes fetched during the
  transfer rather than re-reading the artifact back from storage after writing it.
- **FR-007**: A cache hit MUST continue to be served from local storage without contacting the
  upstream.
- **FR-008**: `maven-metadata.xml` (and any metadata classified as pass-through) MUST continue to be
  served fresh from the upstream on every request and MUST NOT be cached.
- **FR-009**: Upstream `404`/`410` MUST continue to surface as a miss, and upstream
  unreachable/error/unexpected-status MUST continue to surface as an upstream error, with nothing
  cached in either case.
- **FR-010**: The change MUST NOT alter the externally observable repository contract (resolved
  bytes, status codes for hit/miss/error, group first-match semantics, per-repository read
  authorization); only the timing and internal I/O of a miss change.

### Key Entities *(include if feature involves data)*

- **Proxy cache entry**: a locally stored, complete copy of an upstream artifact, keyed by
  repository name + artifact path. Invariant: only ever exists for fully, successfully transferred
  artifacts.
- **In-flight transfer**: the transient state of one cache-miss request — the upstream body being
  simultaneously delivered to the client and written to a pending (not-yet-promoted) cache location.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For a cold-cache artifact, the time from request to the client's first received byte
  is approximately the upstream's time-to-first-byte, rather than the time to download the entire
  artifact from the upstream (no full server-side pre-buffer before first byte).
- **SC-002**: End-to-end per-artifact latency on a cold-cache miss is reduced by roughly half for
  artifacts large enough that transfer time dominates connection overhead.
- **SC-003**: 100% of artifacts cached via streaming are byte-for-byte identical to the upstream
  (identical checksum); zero truncated or partial artifacts are ever served from the cache.
- **SC-004**: Across simulated client-disconnect and mid-stream upstream-failure scenarios, the
  cache contains zero entries for the failed artifacts (0 partial promotions).
- **SC-005**: A cold-cache miss serves the client and populates the cache without a second full read
  of the just-written artifact (one fewer full artifact read per miss than today).
- **SC-006**: A second resolution of the same artifact is served entirely from cache with no
  upstream request.

## Assumptions

- The consuming clients are standard Maven/Gradle resolvers that accept a streamed (including
  chunked, length-unknown) HTTP response — the established repository-contract clients.
- Concurrent first requests for the same uncached artifact may each independently fetch from the
  upstream (as today); de-duplicating concurrent upstream fetches into a single shared download is a
  separate optimization and is out of scope here. The integrity guarantee (no partial promotion)
  still holds for each.
- The existing temp-file-plus-atomic-move durability pattern of the storage layer is the mechanism
  used to guarantee only complete transfers become cache entries; this feature keeps that guarantee.
- Disk write of an artifact is not the dominant cost on a miss (network fetch dominates); this
  feature targets overlap of fetch and delivery plus removal of the redundant read, not disk-write
  throughput.
- This feature applies to filesystem-backed cache storage and any other configured storage backend
  through the same storage abstraction; no backend-specific behavior is assumed beyond the existing
  write/read contract.

## Out of Scope

- De-duplicating or coalescing concurrent upstream fetches for the same artifact.
- Changing cache eviction/retention behavior (feature 009).
- Changing metadata pass-through semantics.
- Any change to hosted (non-proxy) repository read/write behavior.
- Tuning client-side (Gradle/Maven) download parallelism — that is consumer configuration, not a
  server change.
