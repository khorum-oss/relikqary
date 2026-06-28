# Data Model: Proxy (Remote) Repositories & Repository Groups

Entities are configuration and in-memory domain types; no database. Storage keys reuse the existing
`{repo}/{artifactKey}` namespacing.

## RepositoryKind (enum) — new

`HOSTED` | `PROXY` | `GROUP`. Distinguishes how a repository resolves and whether it accepts publishes.

## RepositoryProperties.Repo (extended)

A single configured repository. New/optional fields are additive; existing `{name, type}` config is
unchanged.

| Field | Type | Applies to | Notes |
|-------|------|-----------|-------|
| `name` | String | all | Repository identifier = first path segment. |
| `kind` | RepositoryKind | all | Default `HOSTED`. |
| `type` | RepositoryType | HOSTED | RELEASE / SNAPSHOT / MIXED; governs acceptance + mutability (feature 004). Ignored for PROXY/GROUP. |
| `remoteUrl` | String? | PROXY | Upstream Maven-layout base URL (e.g. `https://repo1.maven.org/maven2`). Required for PROXY. |
| `remoteUsername` | String? | PROXY | Optional upstream Basic-auth user. |
| `remotePassword` | String? | PROXY | Optional upstream Basic-auth secret — supplied via env/file, never committed. |
| `members` | List\<String\> | GROUP | Ordered member repository names. Required (≥1) for GROUP. |

**Validation (at `RepositoryRegistry` construction — fail-fast):**
- `PROXY`: `remoteUrl` non-blank.
- `GROUP`: `members` non-empty; each member resolves to a configured repo; no self-reference; each member
  is `HOSTED` or `PROXY` (no nested groups).
- `name` non-blank and unique (existing behavior).

## RepositoryRegistry (extended)

- `require(name): Repo` — unchanged (throws `RepositoryNotFoundException` → 404).
- `all(): List<Repo>` — unchanged.
- Adds startup validation (above); throws on invalid config so the context fails to start.

## UpstreamClient — new (`proxy/UpstreamClient.kt`)

Fetches a single artifact path from a proxy's upstream.

- `fetch(repo: Repo, artifactPath: String): UpstreamResponse`
- Builds `{remoteUrl}/{artifactPath}`, applies optional Basic auth, uses JDK `HttpClient` honoring
  `ProxySelector.getDefault()`.
- Returns a sealed `UpstreamResponse`:
  - `Found(stream: InputStream, contentLength: Long?)` — HTTP 200.
  - `NotFound` — HTTP 404/410.
  - `Error(cause)` — connection failure, timeout, or upstream 5xx/other.

## Resolution — new (`repository/RepositoryResolver.kt`)

Read-path dispatcher used by the controller (and group recursion).

- `resolve(repoName: String, path: RepositoryPath): Resolution`
- Sealed `Resolution`:
  - `Hit(artifact: StoredArtifact)` → 200
  - `Miss` → 404
  - `UpstreamError` → 502
- Dispatch:
  - **HOSTED** → `storage.openRead("{name}/{path}")` ⇒ `Hit`/`Miss`.
  - **PROXY** → if `path.classify() == METADATA`: pass-through `UpstreamClient.fetch` (`Found`→`Hit`
    streaming upstream; `NotFound`→`Miss`; `Error`→`UpstreamError`), never cached. Else cache-first:
    cached ⇒ `Hit`; otherwise fetch — `Found` ⇒ `storage.write(cacheKey, body)` then `openRead` ⇒ `Hit`;
    `NotFound` ⇒ `Miss` (nothing cached); `Error` ⇒ `UpstreamError`.
  - **GROUP** → iterate `members` in order, recursively resolving each: first `Hit` returns; `Miss`
    continues; `UpstreamError` is remembered. After all members: remembered error ⇒ `UpstreamError`,
    else `Miss`.

## Cache (conceptual)

Locally stored copies of artifacts a proxy has fetched, under storage key `{proxyName}/{artifactPath}`
in the configured backend (filesystem/S3). Byte-for-byte identical to upstream (Principle IV). Only
immutable files are cached; `maven-metadata.xml` is never cached.

## Status mapping (controller)

| Situation | HTTP |
|-----------|------|
| Resolution `Hit` | 200 (octet-stream, content-length) |
| Resolution `Miss` | 404 |
| Resolution `UpstreamError` | 502 |
| `PUT` to PROXY/GROUP (request reaches controller) | 405 (`Allow: GET, HEAD`) |
| Unknown repo name | 404 (existing `RepositoryNotFoundException`) |
| Invalid/traversal path | 400 (existing `InvalidRepositoryPathException`) |
