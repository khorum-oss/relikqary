# Phase 1 Data Model: Core Publish-and-Resolve MVP

Conceptual model for the MVP. These are domain concepts and how they map to storage keys — not a
relational schema (the MVP persists files, not rows).

## ArtifactCoordinate

Identity of a published module.

| Field | Description | Rules |
|-------|-------------|-------|
| `groupId` | Reverse-DNS group, e.g. `com.example` | Non-empty; `.` maps to `/` in the layout path |
| `artifactId` | Module name, e.g. `widget` | Non-empty |
| `version` | Version string, e.g. `1.0.0`, `1.1.0-SNAPSHOT` | Non-empty; classified RELEASE vs SNAPSHOT |
| `kind` (derived) | RELEASE or SNAPSHOT | SNAPSHOT iff `version` ends with `-SNAPSHOT` |

**Layout mapping**: `groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/"` is the
directory prefix for the coordinate's files.

## ArtifactFile

An individual stored object belonging to a coordinate, addressed by its full repository-layout path.

| Field | Description | Rules |
|-------|-------------|-------|
| `path` | Full repo-layout relative path = storage key | Unique; 1:1 with request URI path |
| `bytes` | File content | Stored byte-for-byte identical to upload (FR-003) |
| `classifierType` (derived) | pom / jar / sources / javadoc / checksum / signature / metadata | Derived from filename suffix; informational only |

**Filename conventions** (not enforced beyond storage, but recognized):
`<artifactId>-<version>.jar`, `-sources.jar`, `-javadoc.jar`, `.pom`, sidecars `.sha1`/`.md5`/
`.sha256`/`.sha512`, signatures `.asc`, and `maven-metadata.xml` (+ its sidecars).

## RepositoryMetadata

Per-artifact version listing used for discovery (`maven-metadata.xml`).

| Field | Description | MVP behavior |
|-------|-------------|--------------|
| `path` | `<group-path>/<artifactId>/maven-metadata.xml` | Stored & served as uploaded by the client (research §3) |
| `versions` | Versions available for the coordinate | Carried inside the client-uploaded XML (no server synthesis in MVP) |

## StorageLocation (configuration)

Where files are persisted; swappable without code changes (FR-007).

| Field | Description | Rules |
|-------|-------------|-------|
| `filesystem.root` | Base directory for the filesystem backend | Configured via `relikquary.storage.*`; must be writable |

## RepublishPolicy (configuration + behavior)

Governs `PUT` over an existing coordinate file (FR-010).

| Field | Description | Default |
|-------|-------------|---------|
| `releasePolicy` | `reject` or `overwrite` for existing RELEASE files | `reject` (→ HTTP 409) |
| SNAPSHOT behavior | Existing SNAPSHOT files | Overwrite allowed |

**State transition (a `PUT` to key K):**

```
K absent ............................ store bytes → 201/200
K present, SNAPSHOT coordinate ...... overwrite bytes → 200
K present, RELEASE, policy=reject ... reject, leave stored bytes unchanged → 409
K present, RELEASE, policy=overwrite  overwrite bytes → 200
```

## Relationships

- An `ArtifactCoordinate` owns many `ArtifactFile`s (its jar, pom, sidecars, etc.).
- An `ArtifactCoordinate` (group+artifact) is summarized by one `RepositoryMetadata` listing versions.
- `ArtifactFile`s are persisted into the configured `StorageLocation` via `ArtifactStorage`.
- `RepublishPolicy` mediates whether a `PUT` mutates an existing `ArtifactFile`.
