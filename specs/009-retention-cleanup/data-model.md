# Data Model: Retention & Cleanup Policies

Configuration and in-memory domain types; no database. Builds on the feature 004/006/007 repository
model and the feature 003/005 storage abstraction.

## CleanupProperties (new) — `config/CleanupProperties.kt`

Global cleanup settings (`@ConfigurationProperties("relikquary.cleanup")`).

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `enabled` | Boolean | `false` | Master switch for the scheduled runner. |
| `interval` | Duration | `PT1H` | Fixed delay between scheduled runs. |

## RetentionPolicy (new) — on `RepositoryProperties.Repo`

Optional per-repository `retention` block; absent ⇒ the repo is never cleaned (FR-009).

- `RetentionPolicy(snapshot: SnapshotRetention?, cache: CacheEviction?)`
- `SnapshotRetention(keepLast: Int?, maxAge: Duration?)` — applies to hosted snapshot/mixed repos.
- `CacheEviction(maxAge: Duration?, maxSize: DataSize?)` — applies to proxy repos.

Each leaf field is independently optional; a policy may set one or both dimensions.

## ArtifactStorage.walk (new) — `storage/ArtifactStorage.kt`

- `walk(prefix: String): List<StoredObject>` — every file recursively under `prefix`.
- `StoredObject(key: String, sizeBytes: Long, lastModified: Instant?)`.
- Filesystem: `Files.walk`; S3: paginated `listObjectsV2` (no delimiter). Files only (no directories).

## SnapshotBuild (new, transient) — `cleanup/`

A single timestamped build of a snapshot artifact, the unit of snapshot retention.

| Field | Type | Notes |
|-------|------|-------|
| `versionDir` | String | the `…/{artifact}/{x.y.z-SNAPSHOT}` prefix |
| `buildId` | String | the `yyyyMMdd.HHmmss-N` token shared by the build's files |
| `timestamp` | Instant | parsed from `buildId` (ordering key) |
| `files` | List\<String\> | the storage keys belonging to this build |

Files without a timestamp token (plain `…-SNAPSHOT.ext`, `maven-metadata.xml`, checksums of retained
builds) are not part of any build and are never selected.

## CleanupService (new) — `cleanup/CleanupService.kt`

- `run(dryRun: Boolean): CleanupReport`
- For each configured repository with a `retention` policy:
  - **hosted snapshot/mixed** → group `-SNAPSHOT` builds (D3), select beyond `keepLast` / older than
    `maxAge`, always keep the newest; delete the selected builds' files (or record, if dry-run).
  - **proxy** → select cached files older than `maxAge` and/or oldest-first until within `maxSize`
    (D4); delete or record.
  - **release-only / group / unconfigured** → skipped (never touched).
- Selection helpers are pure (no I/O) for unit testing; deletion is the single mutation point.

## CleanupReport (new DTO) — `protocol/dto/`

| Field | Type | Notes |
|-------|------|-------|
| `dryRun` | Boolean | whether anything was actually deleted |
| `repositories` | List\<RepoCleanupResult\> | per-repo outcome |
| `itemsRemoved` | Int | total files removed (or that would be) |
| `bytesReclaimed` | Long | total bytes reclaimed (or that would be) |

`RepoCleanupResult(name, itemsRemoved, bytesReclaimed)`.

## CleanupScheduler (new) — `cleanup/CleanupScheduler.kt`

`@Component`, `@ConditionalOnProperty("relikquary.cleanup.enabled")`,
`@Scheduled(fixedDelayString="${relikquary.cleanup.interval-ms}")` → `CleanupService.run(dryRun = false)`.
Requires `@EnableScheduling` on the application.

## CleanupController (new) — `protocol/CleanupController.kt`

- `POST /api/cleanup?dryRun={bool}` → `CleanupReport` (JSON). Default `dryRun=false`.
- Authorization: the feature-007 `RepositoryAuthorizationManager` requires the global `PUBLISH` authority
  for `/api/cleanup` (open when `security.enabled=false`).

## Status mapping

| Situation | HTTP |
|-----------|------|
| Cleanup run (or dry-run) completes | 200 + `CleanupReport` |
| Unauthenticated (security enabled) | 401 (Basic challenge / bare 401 for XHR per 008) |
| Authenticated without `PUBLISH` | 403 |
