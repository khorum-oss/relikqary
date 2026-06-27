# Contract: Browse & Manage API

A JSON API under `/api`, separate from the Maven wire protocol (`/{repo}/**`). All paths are within a
configured repository; unknown repo or absent path → `404`. Path traversal → `400`.

## Endpoints

### GET /api/repositories
List configured repositories.
```json
[ { "name": "releases", "type": "RELEASE" }, { "name": "snapshots", "type": "SNAPSHOT" } ]
```

### GET /api/repositories/{repo}/contents/{path?}
List entries directly under `{path}` (empty path = repo root).
```json
{
  "repository": "releases",
  "path": "com/example/widget/1.0.0",
  "entries": [
    { "name": "widget-1.0.0.jar", "kind": "file", "size": 12345, "lastModified": "2026-06-27T10:00:00Z" },
    { "name": "widget-1.0.0.pom", "kind": "file", "size": 678, "lastModified": "2026-06-27T10:00:00Z" }
  ]
}
```
Folders are `{ "name": "1.0.0", "kind": "folder" }` (no size/lastModified).

### GET /api/repositories/{repo}/file/{path}
Details for a single file, including sibling checksum values when present.
```json
{
  "repository": "releases",
  "path": "com/example/widget/1.0.0/widget-1.0.0.jar",
  "size": 12345,
  "lastModified": "2026-06-27T10:00:00Z",
  "checksums": { "sha1": "…", "md5": "…" },
  "downloadUrl": "/releases/com/example/widget/1.0.0/widget-1.0.0.jar"
}
```

### Download
Reuses the Maven protocol: `GET /{repo}/{path}` returns the exact stored bytes.

### DELETE /api/repositories/{repo}/{path}
Delete a single file or, if `{path}` is a folder prefix, all files beneath it. `204` on success,
`404` if nothing matched.

## Auth

- `GET /api/**` and downloads: open (read), consistent with 002.
- `DELETE /api/**`: requires the `PUBLISH` role (`401` unauthenticated, `403` authenticated-without-role)
  when `relikquary.security.enabled=true`; open when disabled.

## Invariants

- Read endpoints never mutate stored bytes (Principle IV); DELETE is the only mutation.
- Behaviour is identical across the filesystem and S3 backends (FR-009).
