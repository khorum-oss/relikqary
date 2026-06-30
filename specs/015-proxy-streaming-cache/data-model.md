# Data Model: Streaming Proxy Cache (Tee)

This feature is behavioral; the "entities" are runtime abstractions, not persisted records.

## ArtifactWrite (NEW)

A pending, not-yet-promoted cache write handle returned by `ArtifactStorage.openWrite(key)`.

| Member | Type | Description |
|--------|------|-------------|
| `sink` | `OutputStream` | Where mirrored bytes are written during the transfer. |
| `commit()` | `() -> Long` | Atomically promote the pending bytes to the cache entry at `key`; returns bytes written. Called once, only on a fully successful transfer. |
| `abort()` | `() -> Unit` | Discard the pending bytes; no cache entry is created. |
| `close()` | `() -> Unit` (Closeable) | Release resources; if neither committed nor aborted, behaves as `abort()` (never leaks a temp artifact). |

**Invariants**:
- `commit()` and `abort()` are mutually exclusive and each idempotent.
- A cache entry at `key` appears **only** after a successful `commit()`.
- Implementations preserve bytes exactly (constitution IV) — no re-encode/re-checksum.

**Backend realizations**:
- Filesystem: temp file in the destination directory; `commit` = `ATOMIC_MOVE` to `key`; `abort` =
  delete temp.
- S3: temp file as `sink`; `commit` = `putObject(key, temp)`; `abort` = delete temp.

## TeeInputStream (NEW)

Wraps the upstream `InputStream` and an `ArtifactWrite`; mirrors every byte delivered to the reader
into `ArtifactWrite.sink`, and resolves the pending write based on how reading terminates.

| Aspect | Behavior |
|--------|----------|
| `read(...)` | Reads from upstream; writes the same bytes to `sink`; tracks whether EOF (`-1`) was observed. |
| read throws `IOException` | Marks the transfer failed (upstream truncation/reset). |
| `close()` | If EOF was observed and no read error → `ArtifactWrite.commit()`; otherwise → `ArtifactWrite.abort()`. Always closes the upstream stream and `sink`. Idempotent. |

**State**: `eofReached: Boolean`, `failed: Boolean`, `resolved: Boolean` (commit/abort happened).

**Maps to requirements**: FR-001 (overlap via read-driven mirror), FR-002/003/004 (promote only on
EOF; abort on early close or read error), FR-005 (byte-for-byte mirror), FR-006 (the served stream
*is* the fetched stream — no re-read).

## StoredArtifact (MODIFIED)

Existing handle returned for a `Resolution.Hit`.

| Field | Change |
|-------|--------|
| `stream` | unchanged (now may be a `TeeInputStream` on a proxy miss). |
| `sizeBytes` | size may be **unknown** for a streamed miss when the upstream omits `Content-Length`. Represented so the controller can distinguish "known" from "unknown" (e.g. a nullable/sentinel value). Cache hits always have a known size. |

## Resolution (UNCHANGED)

`Hit` / `Miss` / `UpstreamError` semantics and the resulting HTTP status codes are unchanged
(FR-009/FR-010). Only what backs a `Hit.artifact.stream` on a proxy miss changes.

## State transitions — one proxy cache-miss request

```text
miss → openWrite(key) [pending] → fetch upstream
    → stream to client (mirror to sink)
        ├─ reader reaches EOF, no error   → commit()  ⇒ cache entry created (Hit served)
        ├─ client disconnects before EOF  → abort()   ⇒ no cache entry (client got partial/none)
        └─ upstream read error / truncate → abort()   ⇒ no cache entry; next request re-fetches
```

Metadata (`maven-metadata.xml`) does not enter this flow — it stays pass-through (never opens a
pending write).
