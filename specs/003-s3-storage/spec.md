# Feature Specification: S3 / DigitalOcean Spaces Storage Backend

**Feature Branch**: `003-s3-storage`

**Created**: 2026-06-26

**Status**: Draft

**Input**: User description: "Add an S3 / DigitalOcean Spaces storage backend behind the existing storage abstraction, selectable by configuration, fulfilling the 'configurable final storage' vision."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Persist artifacts to S3-compatible object storage (Priority: P1)

An operator configures Relikquary to use an S3-compatible backend (AWS S3, DigitalOcean Spaces, or a
compatible endpoint) and a bucket. Published artifacts are stored in that bucket and resolve back
byte-for-byte, exactly as with the filesystem backend.

**Why this priority**: This is the feature — object storage for production deployments where local
disk is not durable/shared.

**Independent Test**: With `backend=s3` pointed at an S3-compatible endpoint and a bucket, publish an
artifact and resolve it back; the bytes match and the object exists in the bucket under its
Maven-layout key.

**Acceptance Scenarios**:

1. **Given** `relikquary.storage.backend=s3` with valid endpoint/bucket/credentials, **When** a client
   publishes an artifact, **Then** the file is stored as an object whose key is the Maven-layout path.
2. **Given** an artifact stored in the bucket, **When** a client resolves it, **Then** the served bytes
   are byte-for-byte identical to what was published.
3. **Given** a key that does not exist, **When** a client requests it, **Then** the response is a clean
   404 (the not-found contract is preserved).

### User Story 2 - Select the backend by configuration (Priority: P1)

The storage backend is chosen by configuration without code changes; filesystem remains the default.

**Why this priority**: Operators must switch between local disk and object storage per environment;
defaulting to filesystem keeps existing deployments unchanged.

**Independent Test**: With no `backend` set (or `filesystem`), storage uses the local filesystem; with
`backend=s3`, storage uses the bucket — same binary, configuration only.

**Acceptance Scenarios**:

1. **Given** no `relikquary.storage.backend` set, **When** the app starts, **Then** the filesystem
   backend is active (default, unchanged behaviour).
2. **Given** `relikquary.storage.backend=s3`, **When** the app starts, **Then** the S3 backend is active
   and the filesystem backend is not.

### Edge Cases

- **Missing object**: `getObject`/`headObject` for an absent key MUST surface as a 404, not a 5xx
  (preserves FR-008 fall-through).
- **Large artifacts**: uploads MUST stream/upload without buffering the whole file in memory.
- **Missing/invalid S3 credentials or bucket at startup**: the app MUST fail fast with a clear error
  rather than silently degrading.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Relikquary MUST support an S3-compatible storage backend implementing the same
  `ArtifactStorage` contract (exists/read/write) as the filesystem backend.
- **FR-002**: The active backend MUST be selectable via configuration
  (`relikquary.storage.backend = filesystem | s3`), defaulting to `filesystem`, with no code change.
- **FR-003**: The S3 backend MUST support a configurable endpoint (for DigitalOcean Spaces / MinIO /
  custom S3), region, bucket, credentials, and path-style access.
- **FR-004**: Stored objects MUST be byte-for-byte identical to what was published, and served back
  identically (faithful storage, Principle IV / 001 FR-003).
- **FR-005**: The Maven-layout request path (validated `RepositoryPath`) MUST be used directly as the
  S3 object key.
- **FR-006**: A request for an absent object MUST yield a 404 (not a 5xx), preserving repository
  fall-through (001 FR-008).
- **FR-007**: S3 credentials MUST be supplied via configuration/environment and MUST NEVER be
  committed.
- **FR-008**: Switching backends MUST NOT change the repository wire layout, the auth behaviour (002),
  or the served bytes — only where bytes are persisted.

### Key Entities

- **Storage Backend Selector**: `relikquary.storage.backend` choosing filesystem vs s3.
- **S3 Settings**: endpoint, region, bucket, credentials, path-style flag.

## Success Criteria *(mandatory)*

- **SC-001**: With `backend=s3` against an S3-compatible endpoint, a published artifact is stored as a
  bucket object and resolves back byte-for-byte.
- **SC-002**: With no backend configured, the filesystem backend is used (default, existing tests still
  pass).
- **SC-003**: A request for a non-existent object returns 404.
- **SC-004**: An end-to-end publish→resolve works with `backend=s3` (real Gradle publish, real
  Maven/Gradle resolve) against an S3-compatible endpoint.

## Assumptions

- An S3-compatible endpoint and bucket are provisioned by the operator; bucket creation/lifecycle is
  out of scope (the bucket exists).
- AWS SDK for Java v2 is used as the S3 client.
- **Testing note**: the constitution mandates Testcontainers + MinIO; this environment has no Docker.
  The S3 backend is verified here against **adobe/s3mock run as an external process** (a real S3
  protocol boundary), and a Testcontainers MinIO test is included but auto-skips without Docker (runs
  in CI). This deviation is explicit and justified, not a silent skip.
