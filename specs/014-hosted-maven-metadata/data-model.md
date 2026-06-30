# Phase 1 Data Model: Server-Side maven-metadata.xml for Hosted Repositories

No persistent schema change — the "model" is the in-memory metadata structures plus the rules for deriving
them from storage. Generated files are written to the same store as ordinary artifacts.

## Path derivation (from a published `RepositoryPath`)

A published coordinate key is `{repo}/{groupPath}/{artifactId}/{version}/{fileName}`.

- `artifactDir` = key minus `"/{version}/{fileName}"` → `{repo}/{groupPath}/{artifactId}`
- `artifactId` = `RepositoryPath.artifactId` (last segment of `artifactDir`)
- `groupId` = `{groupPath}` with `/` → `.` (the segments of `artifactDir` between `{repo}` and `{artifactId}`)
- Artifact-level metadata key = `{artifactDir}/maven-metadata.xml` (+ `.sha1`, `.md5`)
- Version-level snapshot metadata key = `{artifactDir}/{version}/maven-metadata.xml` (+ `.sha1`, `.md5`)

## Enumeration

- **Versions**: `storage.list(artifactDir)` → immediate child directories = version dirs (ignore the
  `maven-metadata.xml*` files at this level). Each version dir's recency = max `lastModified` of its files
  (`storage.walk`).
- **Snapshot builds** (for a `-SNAPSHOT` version): `storage.walk("{artifactDir}/{version}")` → files whose
  name carries the canonical `YYYYMMDD.HHMMSS-N` token (the cleanup `BUILD_TOKEN`); group by build token,
  newest = max by (timestamp, buildNumber).

## Entities (in-memory)

### ArtifactMetadata (artifact-level)
| Field | Source / rule |
|-------|---------------|
| `groupId` | from `artifactDir` |
| `artifactId` | from `artifactDir` |
| `versions` | all version dir names, ordered by ascending recency (deploy order) |
| `release` | newest **non-`-SNAPSHOT`** version by recency; omitted if none |
| `latest` | newest version overall by recency; omitted if none |
| `lastUpdated` | max file `lastModified` across the artifact, UTC `yyyyMMddHHmmss` |

### SnapshotMetadata (version-level, only for `-SNAPSHOT` versions)
| Field | Source / rule |
|-------|---------------|
| `groupId`, `artifactId`, `version` | the `-SNAPSHOT` coordinate |
| `snapshot.timestamp` | newest build's `YYYYMMDD.HHMMSS` |
| `snapshot.buildNumber` | newest build's `N` |
| `lastUpdated` | newest build time, UTC `yyyyMMddHHmmss` |
| `snapshotVersions[]` | one per (classifier, extension) of the newest build: `{ classifier?, extension, value = "{base}-{timestamp}-{N}", updated }` |

`base` = the `-SNAPSHOT` version with the `-SNAPSHOT` suffix replaced by the timestamped form
(e.g. `2.0.0-SNAPSHOT` → value `2.0.0-20260630.120000-3`). Filename parse:
`{artifactId}-{base}-{timestamp}-{N}[-{classifier}].{extension}`.

## Generated XML (byte-faithful Maven layout)

**Artifact-level** `maven-metadata.xml`:
```
metadata > groupId, artifactId, versioning >
  latest?, release?, versions > version*, lastUpdated
```

**Version-level snapshot** `maven-metadata.xml`:
```
metadata > groupId, artifactId, version, versioning >
  snapshot > timestamp, buildNumber
  lastUpdated
  snapshotVersions > snapshotVersion( classifier?, extension, value, updated )*
```

Both are serialized deterministically (StAX), UTF-8, with a stable element order so regeneration is
reproducible. `.sha1`/`.md5` are computed over the exact serialized bytes.

## Write semantics

- Writes go through `ArtifactStorage.write` (atomic temp-file move on filesystem; single PUT on S3) → readers
  never see a half-written metadata file.
- Regeneration for one groupId:artifactId is serialized by an in-process per-coordinate lock (enumerate +
  write artifact-level, and version-level for the affected `-SNAPSHOT`).
- Generating metadata writes **only** the metadata + checksum keys; no other artifact key is touched (FR-008).

## Status / triggers (state transitions)

| Event | Action |
|-------|--------|
| Hosted publish, path is a coordinate file (`classify() != METADATA`) | regenerate artifact-level; if version is `-SNAPSHOT`, also regenerate that version's snapshot metadata |
| Hosted publish, path **is** `maven-metadata.xml` | accept the upload (clients unchanged) but it is superseded by the server's authoritative copy |
| GET hosted `maven-metadata.xml`, stored file missing | compute-on-read, serve, and write through |
| GET on a proxy/group repo | unchanged (pass-through / member resolution) |
| Cleanup removes versions/builds | (out of primary scope) next publish regenerates; read-side fallback recomputes if file absent |
