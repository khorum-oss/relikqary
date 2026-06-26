# Contract: Named Repositories

Supersedes the root addressing of the 001 contract. Within a repository, the Maven layout, faithful
storage, and auth (002) are unchanged.

## Addressing

```
/{repo}/{groupId as path}/{artifactId}/{version}/{filename}
```

- `{repo}` is a configured repository name (e.g. `releases`, `snapshots`). There is **no** implicit
  repository at the root.
- Unknown `{repo}` ⇒ `404` for every method.

Example: `PUT /releases/com/example/widget/1.0.0/widget-1.0.0.jar` →
object key `releases/com/example/widget/1.0.0/widget-1.0.0.jar`.

## Per-repo publish rules (PUT)

| Repo type | release coordinate | `-SNAPSHOT` coordinate | existing target |
|-----------|--------------------|------------------------|-----------------|
| `release` | accept             | `400` (type mismatch)  | `409` (immutable, unless overwrite configured) |
| `snapshot`| `400`              | accept                 | overwrite (`200`) |
| `mixed`   | accept             | accept                 | release `409` / snapshot+metadata overwrite |

- `maven-metadata.xml` (and its checksums) are always overwritable, in any repo type.
- New target (no existing object) ⇒ stored, `201`.

## GET / HEAD

- Unchanged within a repo: serve stored bytes (200) or clean `404` when absent (preserves
  fall-through). Unknown repo ⇒ `404`.

## Invariants

- **Namespacing**: identical coordinates in different repos are independent objects (FR-007).
- **Faithful bytes & no wire change** inside a repo (001 invariants preserved).
- **Path safety**: traversal in the repo segment or artifact path ⇒ `400` (FR-012).
