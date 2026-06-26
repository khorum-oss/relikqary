# Feature Specification: Multiple Named Repositories

**Feature Branch**: `004-named-repositories`

**Created**: 2026-06-26

**Status**: Draft

**Input**: User description: "Support multiple named, typed repositories (e.g. releases, snapshots) addressed by a path prefix, instead of a single implicit repo. Repo type governs what it accepts and mutability. Explicit repo in path, no root default."

## Clarifications

### Session 2026-06-26

- Q: Addressing? → A: Explicit repo in path (`/{repo}/{group}/…`), no root default (breaking vs 001).
- Q: Repo types? → A: Typed — `release` (only releases, immutable), `snapshot` (only `-SNAPSHOT`,
  overwritable), `mixed` (both, version-string rule). Type governs acceptance AND mutability.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Publish/resolve against named repositories (Priority: P1)

A maintainer points a Gradle build at `http://host/releases` (or `/snapshots`) and publishes; a
consumer resolves from the same repo URL. Artifacts in different repos are isolated.

**Why this priority**: This is the feature — separately addressable repositories.

**Independent Test**: Publish a release to `/releases` and resolve it back from `/releases`
byte-for-byte; confirm it is namespaced under that repo.

**Acceptance Scenarios**:

1. **Given** a configured `releases` repo, **When** a client publishes `com.example:widget:1.0.0` to
   `/releases/com/example/widget/1.0.0/…`, **Then** it is stored and resolvable from that path.
2. **Given** a request to an undefined repo name, **When** any method is used, **Then** the response is
   `404`.

### User Story 2 - Repo type governs acceptance and mutability (Priority: P1)

Each repo's type restricts what it accepts and whether existing artifacts may be overwritten.

**Why this priority**: Release immutability and snapshot mutability are the core value of typed repos.

**Independent Test**: Re-publishing a release to `/releases` is rejected (409); publishing a
`-SNAPSHOT` to `/releases` is rejected (400); re-publishing a snapshot to `/snapshots` overwrites.

**Acceptance Scenarios**:

1. **Given** a `release` repo, **When** an existing release coordinate is re-published, **Then** `409`
   and the stored bytes are unchanged.
2. **Given** a `release` repo, **When** a `-SNAPSHOT` coordinate is published, **Then** `400` (type
   mismatch).
3. **Given** a `snapshot` repo, **When** a release (non-SNAPSHOT) coordinate is published, **Then**
   `400`.
4. **Given** a `snapshot` repo, **When** an existing snapshot is re-published, **Then** it overwrites
   (`200`).

### Edge Cases

- **Unknown repo**: first path segment is not a configured repo → `404`.
- **Repo segment only / empty artifact path** (e.g. `GET /releases/`) → `400` (invalid artifact path).
- **Metadata files** (`maven-metadata.xml`) are always overwritable regardless of repo type.
- **Path traversal** in either the repo segment or artifact path → `400` (FR-012 preserved).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Relikqary MUST serve multiple named repositories, each addressed by a path prefix
  `/{repo}/…`; there is no implicit repository at the root.
- **FR-002**: Repositories MUST be defined in configuration as `{name, type}` with no code change;
  defaults MUST include a `releases` (release) and a `snapshots` (snapshot) repo.
- **FR-003**: A request whose first path segment is not a configured repository MUST return `404`.
- **FR-004**: A `release` repo MUST accept only release versions and reject `-SNAPSHOT` coordinates
  with `400`; an existing release coordinate MUST be immutable (`409`) unless overwrite is configured.
- **FR-005**: A `snapshot` repo MUST accept only `-SNAPSHOT` coordinates (reject releases with `400`)
  and MUST allow overwriting existing snapshots.
- **FR-006**: A `mixed` repo MUST accept both and apply the version-string mutability rule (existing
  release immutable, snapshot/metadata overwritable).
- **FR-007**: Artifacts MUST be namespaced per repository so identical coordinates in different repos
  do not collide (the repo name is part of the storage key).
- **FR-008**: The artifact wire layout within a repo, faithful storage, and auth (002) MUST be
  unchanged; only the required repo prefix and per-repo policy are added.

### Key Entities

- **Repository**: a named, typed storage namespace (`name`, `type ∈ {release, snapshot, mixed}`).
- **Repository Registry**: the configured set of repositories, looked up by name.

## Success Criteria *(mandatory)*

- **SC-001**: A real Gradle publish to `/releases` and resolve via real Maven + Gradle from `/releases`
  round-trips byte-for-byte.
- **SC-002**: Re-publishing a release to a release repo returns `409` with bytes unchanged; a snapshot
  re-publish to a snapshot repo overwrites.
- **SC-003**: A SNAPSHOT to a release repo (and a release to a snapshot repo) returns `400`.
- **SC-004**: A request to an undefined repo returns `404`.
- **SC-005**: The same coordinate published to two different repos is stored independently.

## Assumptions

- A single storage backend (filesystem or S3) holds all repos, namespaced by the repo name prefix in
  the key; per-repo storage targets are out of scope.
- Authentication remains global (publishing to any repo requires the `PUBLISH` role); per-repo
  authorization is a later spec.
- Proxy/remote repositories (caching upstream) are out of scope; these are hosted repos only.
