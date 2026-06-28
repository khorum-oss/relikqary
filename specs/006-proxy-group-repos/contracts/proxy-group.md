# Contract: Proxy & Group Repositories

Extends the Maven wire protocol (feature 004, `/{repo}/**`). Proxy and group repos are addressed exactly
like hosted repos — by the first path segment — and resolve via standard `GET`/`HEAD`. They are
**read-only**.

## Configuration

Defined in the same `relikquary.repositories` list as hosted repos, distinguished by `kind`:

```yaml
relikquary:
  repositories:
    - name: releases            # hosted (unchanged)
      type: release
    - name: snapshots
      type: snapshot
    - name: maven-central       # proxy
      kind: proxy
      remoteUrl: https://repo1.maven.org/maven2
      # remoteUsername / remotePassword optional (env/file, never committed)
    - name: public              # group
      kind: group
      members: [releases, maven-central]
```

Invalid config (proxy without `remoteUrl`; group with no members, an unknown member, a self-reference,
or a nested group) fails context startup.

## Proxy repository

### GET /{proxy}/{path}
- **Cache hit** → `200` with the cached bytes (no upstream contact).
- **Cache miss, immutable file** → fetch `{remoteUrl}/{path}`; on upstream `200`, store byte-for-byte and
  return `200`; the artifact is now cached.
- **`maven-metadata.xml`** → always fetched fresh from the upstream (pass-through), never cached.
- **Upstream not found** (`404`/`410`) → `404`; nothing cached.
- **Upstream unreachable / error / 5xx** → `502`; nothing cached.

### PUT /{proxy}/{path}
- `405 Method Not Allowed` (`Allow: GET, HEAD`). (When security is enabled, an unauthenticated PUT still
  receives `401` first.)

## Group repository

### GET /{group}/{path}
- Resolve members in configured order; return the **first** member that has (or can fetch) the path.
- All members miss → `404`.
- No member hit but a proxy member errored upstream → `502`.
- Applies to `maven-metadata.xml` too (first-match; no cross-member merge).

### PUT /{group}/{path}
- `405 Method Not Allowed` (`Allow: GET, HEAD`).

## Download / faithful storage

Resolution returns the exact stored (or upstream) bytes. Cached proxy bytes are identical to the
upstream's, preserving its checksums and signatures (Principle IV).

## Auth

- `GET`/`HEAD` on proxy and group repos: open (read), consistent with features 002/004.
- Upstream credentials (when configured) are used only for the proxy→upstream fetch and are never exposed
  to resolving clients.

## Browse API (additive)

- `GET /api/repositories` includes each repository's `kind`.
- A proxy's cached contents are browsable via the existing storage-backed listing endpoints.
- `GET /api/repositories/{group}/contents/**` returns an empty listing (group browse aggregation is out
  of scope).

## Invariants

- Read endpoints other than a proxy cache-fill never mutate stored bytes; a proxy cache-fill writes the
  upstream bytes unchanged.
- Behaviour is identical across the filesystem and S3 storage backends.
