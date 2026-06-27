# Feature Specification: Web UI for Browsing & Managing Artifacts

**Feature Branch**: `005-frontend-ui`

**Created**: 2026-06-27

**Status**: Draft

**Input**: User description: "Address the frontend. Use SvelteKit; be able to see the items in the repos and all the details, to manage artifacts."

## Clarifications

### Session 2026-06-27

- Q: Manage scope? → A: Browse + delete (view repos/artifacts/versions/file details, and delete a
  version/file). Uploading via the UI is deferred (publishing stays via the Maven flow).
- Q: Serving model? → A: SvelteKit as a separate module that can run/deploy standalone, with an
  opt-in build step that bundles its static output into the backend (single deployable when desired).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Browse repositories and artifacts (Priority: P1)

An operator opens the web UI, sees the configured repositories, and drills into a repository to
explore its contents (group/artifact/version folders down to individual files).

**Why this priority**: Seeing what's stored is the core ask and the foundation for everything else.

**Independent Test**: With artifacts published to `releases`, open the UI, see `releases` listed,
navigate into `com/example/widget/1.0.0/`, and see the stored files.

**Acceptance Scenarios**:

1. **Given** repositories are configured, **When** the UI loads, **Then** it lists each repository
   with its name and type.
2. **Given** a repository with stored artifacts, **When** the operator opens it and navigates a path,
   **Then** the UI shows the folders and files at that path.

### User Story 2 - View artifact/file details (Priority: P1)

For a selected file the operator sees its details — name, full path/coordinate, size, last-modified,
available checksums — and can download it.

**Independent Test**: Select a stored `.jar`; the UI shows its size, last-modified, and a working
download link.

**Acceptance Scenarios**:

1. **Given** a stored file, **When** the operator selects it, **Then** the UI shows size,
   last-modified, and any sibling checksum values (`.sha1`/`.md5`/…).
2. **Given** a stored file, **When** the operator clicks download, **Then** the original bytes are
   downloaded.

### User Story 3 - Delete an artifact or version (Priority: P2)

An authorized operator deletes a file, or a whole version/artifact folder, and the UI reflects the
removal.

**Why this priority**: The "manage" half; depends on browsing existing first.

**Independent Test**: Delete a published version via the UI; afterwards it no longer appears and
resolving it returns 404.

**Acceptance Scenarios**:

1. **Given** authentication is enabled, **When** an operator deletes without/with invalid
   credentials, **Then** the delete is refused (401).
2. **Given** valid credentials, **When** the operator deletes a version folder, **Then** all its files
   are removed and it disappears from the listing.

### Edge Cases

- **Empty repository**: a configured repo with no artifacts shows an empty listing, not an error.
- **Unknown repo/path** in the API → 404.
- **Path traversal** in browse/delete paths → rejected (reuse FR-012 validation).
- **Delete of a non-existent path** → 404 (idempotent-safe message).
- **Auth disabled** (`relikquary.security.enabled=false`) → delete works without credentials (local dev).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The backend MUST expose a JSON browse API (separate from the Maven protocol) to list
  configured repositories and to list the entries (folders/files) under a given repository path.
- **FR-002**: For a file, the API MUST return its details: repository-relative path, size,
  last-modified, and the values of any sibling checksum files present.
- **FR-003**: The API MUST allow downloading a stored file's original bytes (may reuse the existing
  Maven GET path).
- **FR-004**: The backend MUST support deleting a single file and recursively deleting a "folder"
  (version or artifact prefix) within a repository.
- **FR-005**: Browse/list/details/download MUST be readable without credentials when auth is enabled
  (consistent with open read); DELETE MUST require the `PUBLISH` role (401/403), and MUST be open when
  `relikquary.security.enabled=false`.
- **FR-006**: Browse and delete paths MUST be validated against traversal/escape (reuse the existing
  path-safety rules); unknown repository or absent path MUST return 404.
- **FR-007**: A SvelteKit web UI MUST let a user list repositories, browse contents, view file
  details, download files, and delete a file/version.
- **FR-008**: The SvelteKit app MUST be a separate module runnable/deployable on its own, with an
  opt-in build step that bundles its static output to be served by the backend at the same origin.
- **FR-009**: Browsing/deleting MUST work regardless of the active storage backend (filesystem or S3).

### Key Entities

- **Repository summary**: name, type.
- **Listing entry**: name, kind (folder/file), and for files size + last-modified.
- **File details**: repository-relative path, size, last-modified, checksums.

## Success Criteria *(mandatory)*

- **SC-001**: With artifacts published, the UI lists repositories and browses to an artifact's files.
- **SC-002**: The UI shows a file's size/last-modified/checksums and downloads its exact bytes.
- **SC-003**: An authorized delete of a version removes its files; the version no longer lists and
  resolving it returns 404; an unauthenticated delete (auth on) is refused.
- **SC-004**: Browse and delete behave identically on the filesystem and S3 backends.
- **SC-005**: The frontend builds and runs standalone (dev) and its static output can be bundled into
  and served by the backend.

## Assumptions

- Uploading artifacts through the UI is out of scope (publishing remains via the Maven client flow).
- The browse API exposes a filesystem-style tree of the stored layout; richer coordinate-grouped
  views are a later enhancement.
- A single backend holds all repos; listing uses the storage backend's native listing
  (filesystem walk / S3 list-with-delimiter).
