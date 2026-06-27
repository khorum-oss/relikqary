# Feature Specification: Core Publish-and-Resolve MVP

**Feature Branch**: `001-publish-resolve-mvp`

**Created**: 2026-06-26

**Status**: Draft

**Input**: User description: "Core publish-and-resolve MVP for the Relikquary artifact repository — accept a Gradle maven-publish upload, store it via a configurable filesystem backend, and serve it back in Maven-compatible layout so Maven AND Gradle clients resolve it. Defer Gradle Module Metadata specifics, S3/Spaces, auth, multi-repo."

## Clarifications

### Session 2026-06-26

- Q: When an uploaded file's checksum sidecar does not match the uploaded bytes, reject or store as
  received? → A: Store as received (faithful storage); leave verification to the consuming client.
  Keep the design open to a future configurable strict-validation mode, togglable globally and
  overridable per coordinate. Enforcement is out of scope for this MVP.
- Q: When a release coordinate that already exists is re-published, reject, idempotent-if-identical,
  or overwrite? → A: Make it a configurable policy. Default to standard Maven semantics: an existing
  RELEASE coordinate is immutable and a re-publish is rejected with a conflict, while a SNAPSHOT
  coordinate may be overwritten. Operators can change the policy without code changes.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Publish a library artifact (Priority: P1)

A library author points their Gradle build's publishing repository at a Relikquary URL and runs the
publish task. Relikquary accepts the artifact and its associated files (POM, primary jar, optional
sources/javadoc jars, per-file checksums, optional signatures) and stores them durably so they can
be retrieved later, exactly as uploaded.

**Why this priority**: Without ingestion there is nothing to resolve. Accepting a real
`maven-publish` upload is the foundational half of the round-trip and the first thing that must work.

**Independent Test**: Run a real Gradle `maven-publish` task against a running Relikquary instance
and confirm the publish task reports success and every uploaded file is present in the configured
storage location, byte-for-byte identical to what Gradle sent.

**Acceptance Scenarios**:

1. **Given** an empty Relikquary instance with a configured storage location, **When** a Gradle build
   publishes `com.example:widget:1.0.0` (POM + jar + checksums), **Then** the publish task succeeds
   and all uploaded files exist at their Maven-layout coordinate paths in storage.
2. **Given** a publish that also includes sources, javadoc, and detached signature files, **When** the
   publish completes, **Then** every one of those associated files is stored unaltered.
3. **Given** an artifact file and its client-supplied checksum sidecar, **When** both are uploaded,
   **Then** the stored checksum value matches the bytes Relikquary stored for that artifact.

---

### User Story 2 - Resolve a published artifact from Maven and Gradle (Priority: P1)

A consumer adds the same Relikquary URL as a repository in a separate Maven or Gradle project and
declares a dependency on the published coordinates. Resolution succeeds and the consumer receives
the identical artifact, with checksums verifying.

**Why this priority**: Serving artifacts back to unmodified standard clients is the other half of the
round-trip and the core value proposition. It is equal in priority to publishing; together they form
the MVP.

**Independent Test**: Against a Relikquary instance that already has `com.example:widget:1.0.0`
stored, run a real Maven resolution and a real Gradle resolution of that dependency and confirm both
download the artifact and verify its checksum, producing files byte-for-byte identical to the
originals.

**Acceptance Scenarios**:

1. **Given** `com.example:widget:1.0.0` is published, **When** a Maven client with Relikquary as a
   repository resolves that dependency, **Then** the jar and POM download successfully and the
   client's checksum verification passes.
2. **Given** the same published artifact, **When** a Gradle client with Relikquary as a repository
   resolves that dependency, **Then** resolution succeeds and the downloaded files are byte-for-byte
   identical to those a Maven client receives.
3. **Given** a published version, **When** a client requests the version-discovery metadata
   (`maven-metadata.xml`) for the artifact, **Then** Relikquary returns metadata listing the
   available version(s) such that the client can resolve them.

---

### User Story 3 - Redirect storage to a different location (Priority: P2)

An operator changes Relikquary's configured storage location and restarts (or starts a second
instance), and subsequent publishes are persisted to the new location — without any code change.

**Why this priority**: "Configurable final storage location" is an explicit product requirement, but
it can be validated after the basic round-trip works; it gates deployment flexibility rather than the
core demo.

**Independent Test**: Start Relikquary pointed at location A, publish an artifact, confirm it lands in
A; reconfigure to location B, publish another artifact, confirm it lands in B and A is untouched —
all via configuration only.

**Acceptance Scenarios**:

1. **Given** Relikquary configured to store at location A, **When** an artifact is published, **Then**
   its files appear under location A.
2. **Given** Relikquary reconfigured to store at location B, **When** a new artifact is published,
   **Then** its files appear under location B and nothing is written to location A.

---

### Edge Cases

- **Unknown coordinate**: A client requests a groupId/artifactId/version (or a specific file) that
  was never published — Relikquary responds with a not-found result that standard Maven/Gradle clients
  treat as "artifact unavailable" (so they can fall through to other repositories) rather than an
  error that aborts resolution.
- **Re-publishing a release coordinate**: A release version that already exists is published again.
  Under the default policy a published RELEASE coordinate is immutable, so the re-publish is rejected
  with a conflict and the stored release is left unchanged; a SNAPSHOT coordinate may be overwritten.
  The policy is operator-configurable (see FR-010).
- **Partial artifact set**: Only some files of a coordinate are present (e.g. the POM uploaded but the
  jar upload never arrived, or an interrupted publish). A request for a present file succeeds; a
  request for an absent file returns not-found. Resolution of a dependency whose required files are
  incomplete fails on the client side as a normal missing-artifact, not as a server error.
- **Path traversal / malicious path**: A request targets a path containing `..` or other escape
  segments. Relikquary rejects it with a 400 and never resolves the path outside the storage root
  (FR-012).
- **Checksum mismatch on upload**: A client uploads a file together with a checksum sidecar whose
  value does not match the uploaded bytes. Relikquary stores both exactly as received (default
  faithful-storage behavior); the consuming client detects the mismatch on download. See FR-009.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Relikquary MUST accept uploads of artifact files addressed by Maven coordinates
  (groupId, artifactId, version) at their standard Maven-repository-layout paths, covering at minimum
  the POM, the primary jar, optional sources and javadoc jars, per-file checksum sidecars, and
  optional detached signature files.
- **FR-002**: Relikquary MUST persist every uploaded file durably to the configured storage location
  so it survives process restarts.
- **FR-003**: Relikquary MUST store and later serve each file byte-for-byte identical to what was
  uploaded; it MUST NOT silently alter, re-encode, re-compress, re-checksum, or strip signatures from
  stored artifacts.
- **FR-004**: Relikquary MUST serve previously published files back at the same Maven-layout paths so
  that unmodified standard Maven and Gradle clients can download them.
- **FR-005**: Relikquary MUST provide the version-discovery repository metadata (e.g.
  `maven-metadata.xml`) needed for standard clients to discover and resolve the published version(s)
  of an artifact.
- **FR-006**: Relikquary MUST faithfully store and serve client-supplied checksum and signature
  sidecar files so that client-side integrity verification can succeed against the served bytes.
- **FR-007**: The storage location MUST be configurable such that an operator can point Relikquary at
  different filesystem locations without modifying or rebuilding code.
- **FR-008**: When a requested coordinate or file does not exist, Relikquary MUST return a not-found
  response that standard Maven and Gradle clients interpret as "artifact not present in this
  repository" (allowing normal repository fall-through), not a failure that aborts the build.
- **FR-009**: When an uploaded file is accompanied by a checksum sidecar whose value does not match
  the uploaded bytes, Relikquary MUST store the bytes and the sidecar exactly as received (default
  faithful-storage behavior) and leave integrity verification to the consuming client, which verifies
  on download.
- **FR-009a**: The design MUST NOT preclude a future configurable strict-validation mode that rejects
  checksum-mismatched uploads, togglable globally and overridable per coordinate. Implementing that
  enforcement is out of scope for this MVP; only the default store-as-received behavior is required
  now.
- **FR-010**: Re-publish behavior MUST be governed by an operator-configurable policy, changeable
  without code changes. By default Relikquary MUST follow standard Maven semantics: an existing RELEASE
  coordinate is immutable and a re-publish of it MUST be rejected with a conflict while leaving the
  stored release unchanged, whereas a SNAPSHOT coordinate MAY be overwritten.
- **FR-011**: A consumer MUST be able to resolve a published artifact using an unmodified Gradle
  client AND an unmodified Maven client, with both receiving byte-for-byte identical files.
- **FR-012**: Relikquary MUST reject any request path that attempts to escape the repository root
  (e.g. `..` path-traversal segments, absolute-path escapes, empty segments) with a `400 Bad Request`,
  so that no upload or download can read or write outside the configured storage location.

### Key Entities *(include if feature involves data)*

- **Artifact Coordinate**: The identity of a published module — groupId, artifactId, version — that
  maps to a location in the Maven repository layout.
- **Artifact File**: An individual stored object belonging to a coordinate (POM, primary jar, sources
  jar, javadoc jar, checksum sidecar, or signature sidecar), identified by its repository-layout path
  and preserved exactly as uploaded.
- **Repository Metadata**: The per-artifact version-listing information (e.g. `maven-metadata.xml`)
  that lets clients discover which versions of a coordinate are available.
- **Storage Location**: The configured destination where artifact files are durably persisted;
  swappable via configuration without code changes.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A real Gradle `maven-publish` run against a running Relikquary instance completes
  successfully and reports the artifact as published.
- **SC-002**: A real Maven client configured with Relikquary as a repository resolves and downloads a
  previously published artifact, and the client's own checksum verification passes.
- **SC-003**: A real Gradle client configured with Relikquary as a repository resolves and downloads
  that same artifact, and the files it receives are byte-for-byte identical to those the Maven client
  receives.
- **SC-004**: For every published file, the bytes served back equal the bytes uploaded (verified by
  comparing content hashes of uploaded vs. downloaded files) in 100% of cases.
- **SC-005**: Changing only the configured storage location causes new publishes to be persisted to
  the new location, with no code change and no writes to the previous location.
- **SC-006**: A request for a never-published coordinate yields a not-found result that does not
  abort a standard client's resolution (the client proceeds to other configured repositories).
- **SC-007**: Under the default policy, re-publishing an existing RELEASE coordinate is rejected with
  a conflict and the previously stored files are byte-for-byte unchanged, while re-publishing a
  SNAPSHOT coordinate replaces its contents.
- **SC-008**: A request whose path contains traversal segments (e.g. `../`) is rejected with a 400 and
  cannot read or write any file outside the configured storage root.

## Assumptions

- Publishing and reading are open/trusted in this slice; authentication and authorization are out of
  scope and handled by a later feature.
- Only a filesystem-based storage location is in scope; remote/object backends (S3 / DigitalOcean
  Spaces) are deferred, but the configurability requirement (FR-007) is designed so additional
  backends can be added later without changing the publish/resolve contract.
- Gradle clients resolve via the Maven repository layout in this slice; Gradle Module Metadata
  (`.module`) specific handling and feature-variant selection are deferred to a later feature.
- A single, implicit repository is served; multiple named repositories and repository management are
  out of scope.
- Release coordinates are the primary target. The default re-publish policy treats SNAPSHOT
  coordinates as overwritable (FR-010), but SNAPSHOT-specific unique-timestamp/build-number metadata
  semantics are out of scope beyond whatever minimal metadata a basic resolve needs.
- Standard, unmodified Maven and Gradle clients are the consumers; no custom client is required.
