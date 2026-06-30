# Feature Specification: Server-Side maven-metadata.xml for Hosted Repositories

**Feature Branch**: `claude/spec-014-hosted-maven-metadata`

**Created**: 2026-06-30

**Status**: Draft

**Input**: User description: "Server-side maven-metadata.xml generation for hosted repositories. Today Relikquary stores the maven-metadata.xml a client uploads verbatim and never generates/merges it server-side. With independent or sequential publishers, each client computes maven-metadata.xml from its own local view, so a publish from one machine overwrites the artifact-level version listing with one that omits versions published elsewhere — breaking version ranges, latest/release markers, and snapshot resolution. Make Relikquary the authority for hosted-repository metadata: after a publish, the server (re)builds the artifact-level maven-metadata.xml for that groupId:artifactId from what is actually stored, with correct latest/release/lastUpdated, keep checksums consistent, behave correctly for release/snapshot/mixed hosted repos, stay byte-faithful to the Maven layout contract, leave proxy metadata pass-through and all other behavior unchanged, reuse existing storage enumeration and the snapshot timestamp format, and verify with real Maven and Gradle clients after multi-version and multi-publisher scenarios."

## Clarifications

### Session 2026-06-30

- Q: When should the server build the authoritative artifact-level maven-metadata.xml for a hosted artifact? → A: **Hybrid** — regenerate authoritative metadata (and its checksums) on each publish AND fall back to compute-on-read when the stored metadata is missing or stale, so reads are cheap in the common case but never serve a wrong/absent listing.
- Q: Should the server also rebuild version-level snapshot metadata, or only the artifact-level listing? → A: **Both (full fidelity)** — rebuild the artifact-level version listing AND the per-`-SNAPSHOT` version-level metadata (the `<snapshotVersions>`/timestamp listing), so snapshot resolution is server-authoritative across publishers.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Reliable version listing across independent publishers (Priority: P1)

Two different machines (or CI jobs) publish different release versions of the same library to a hosted repository — neither aware of the other's versions. A consumer then asks for the artifact's available versions (or a version range). The consumer sees **every** published version, with correct "latest" and "release" markers, regardless of publish order or which client published last.

**Why this priority**: This is the core defect. Without server-authoritative metadata, the second publisher's upload silently erases the first publisher's versions from the listing, so version-range and latest/release resolution become wrong the moment more than one publisher is involved. Fixing this is the whole point of the feature and the foundation for the snapshot case.

**Independent Test**: Publish version A from one client and version B from another (each uploading its own local metadata), then read the served artifact-level metadata and resolve a version range / latest with a real client — both A and B appear and resolve correctly.

**Acceptance Scenarios**:

1. **Given** version 1.0.0 was published, **When** version 2.0.0 is later published by a different client whose uploaded metadata lists only 2.0.0, **Then** the served artifact-level metadata lists both 1.0.0 and 2.0.0.
2. **Given** several releases exist, **When** a consumer reads the artifact metadata, **Then** "release" is the highest release version, "latest" reflects the newest published version, and "lastUpdated" advances on each publish.
3. **Given** the served metadata, **When** a client validates it against its published checksum, **Then** the metadata's `.sha1`/`.md5` match the served bytes (no integrity failure).

---

### User Story 2 - Correct snapshot resolution (Priority: P2)

A project depends on a `-SNAPSHOT` version hosted in Relikquary. Multiple snapshot builds (and possibly multiple publishers) push timestamped builds. A consumer resolving the snapshot gets the genuinely newest build the server holds, and the artifact's version listing includes the snapshot version.

**Why this priority**: Snapshots are the second way multi-publisher metadata corruption bites, and CI-heavy workflows lean on them. It builds on US1's enumeration but adds snapshot-specific listing/markers, so it follows P1.

**Independent Test**: Publish multiple timestamped snapshot builds (from one or more clients), then resolve the `-SNAPSHOT` dependency with a real client and confirm it gets the newest build the server actually stores, and that the snapshot version appears in the artifact-level listing.

**Acceptance Scenarios**:

1. **Given** a `-SNAPSHOT` version has been published, **When** a consumer reads the artifact-level metadata, **Then** the `-SNAPSHOT` version is listed alongside any releases.
2. **Given** multiple timestamped builds of the same snapshot exist, **When** a consumer resolves that snapshot, **Then** it resolves to the newest build present in storage (not a stale or missing one).

---

### User Story 3 - Mixed and per-type repositories behave correctly (Priority: P3)

An operator runs release-only, snapshot-only, and mixed hosted repositories. Server-generated metadata reflects exactly what each repository is allowed to hold, and proxy/group repositories are unaffected (their metadata still comes straight from upstream).

**Why this priority**: Correct behavior across the existing repository types is necessary for trust, but it's a correctness-refinement on top of US1/US2 rather than a new capability.

**Independent Test**: Exercise release, snapshot, and mixed hosted repos and confirm each serves a correct listing; confirm a proxy repo's metadata is still served from upstream unchanged.

**Acceptance Scenarios**:

1. **Given** a mixed hosted repo with both releases and snapshots, **When** the artifact metadata is read, **Then** it lists both, with "release" pointing at the highest release and "latest" at the newest overall.
2. **Given** a proxy (remote) repository, **When** its metadata is requested, **Then** it is served from upstream as before — the server does not generate or override it.
3. **Given** an artifact with exactly one version, **When** its metadata is read, **Then** the listing is correct (single version; release/latest set appropriately).

---

### Edge Cases

- **Client-uploaded metadata is stale or partial**: The client still uploads a `maven-metadata.xml`; the server's authoritative listing must reflect actual stored versions regardless of what the client uploaded (the client's version list never wins).
- **Concurrent publishes of the same artifact**: Two publishes for the same groupId:artifactId at nearly the same time must not leave a metadata listing that is missing a just-stored version or is corrupt/half-written; the final served metadata reflects all versions present in storage.
- **Checksums of generated metadata**: When the server writes metadata, its `.sha1`/`.md5` siblings must correspond to the exact served bytes; a client-uploaded checksum that no longer matches must not be served as truth.
- **Deletion/cleanup**: When a version (or snapshot build) is removed (e.g. retention cleanup), the version listing should no longer advertise a version that is no longer present. (At minimum, generated metadata must not be falsified into listing absent versions.)
- **Snapshot timestamp parsing**: Snapshot builds use the canonical `YYYYMMDD.HHMMSS-buildNumber` form already understood by the system; non-conforming or non-unique snapshot files must be handled without producing corrupt metadata.
- **Non-artifact paths**: Group-level or unexpected metadata locations (Maven defines artifact-level `maven-metadata.xml` at `<groupPath>/<artifactId>/maven-metadata.xml`) must not cause errors; the feature targets artifact-level metadata.
- **Byte-faithful resolution of everything else**: Generating metadata must not alter any other stored artifact (POMs, jars, checksums, signatures stay exactly as published).
- **Proxy/group untouched**: Metadata for proxy/remote repositories is never generated server-side (pass-through preserved); group repositories continue to resolve members as before.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: After an artifact is published to a hosted repository, the system MUST (re)build the artifact-level `maven-metadata.xml` for that groupId:artifactId from the versions actually present in storage, so the served listing is authoritative and independent of any single client's uploaded metadata. The system MUST also serve a correct authoritative listing when the stored metadata is missing (compute-on-read fallback), per the hybrid strategy; because regeneration runs on every publish, the stored copy is otherwise kept fresh.
- **FR-002**: The generated artifact-level metadata MUST list all stored versions of the artifact and set "release" to the highest release (non-snapshot) version, "latest" to the newest published version, and "lastUpdated" to reflect the most recent publish.
- **FR-003**: The system MUST keep the generated metadata's checksum siblings (`.sha1`, and `.md5` where applicable) consistent with the exact served metadata bytes, so client-side integrity verification succeeds.
- **FR-004**: For snapshot versions, the system MUST rebuild the version-level snapshot metadata (the per-`-SNAPSHOT` `<snapshotVersions>`/timestamp listing) from the timestamped builds actually stored, so a client resolves the newest build the server holds; and the artifact-level listing MUST include snapshot versions — both using the existing canonical snapshot timestamp/build format.
- **FR-005**: The system MUST produce correct metadata for release, snapshot, and mixed hosted repository types, consistent with what each type is permitted to store.
- **FR-006**: The system MUST NOT generate, merge, or override metadata for proxy/remote or group repositories — their metadata behavior (proxy pass-through; group member resolution) MUST remain exactly as today.
- **FR-007**: Concurrent or rapidly-sequential publishes of the same artifact MUST NOT result in served metadata that omits an already-stored version or that is corrupt/partially written; the served metadata MUST converge to reflect all versions present in storage.
- **FR-008**: Generating metadata MUST NOT modify any other stored artifact (POM, jar, sources, javadoc, checksums, signatures remain byte-for-byte as published).
- **FR-009**: The served metadata MUST remain byte-faithful to the Maven repository-layout contract such that unmodified Maven and Gradle clients consume it without error.
- **FR-010**: All existing publish, resolve, authentication/authorization, proxy, group, and cleanup behavior MUST remain unchanged except for the hosted metadata now being server-authoritative.
- **FR-011**: The feature MUST reuse the existing storage version-enumeration capability and the existing snapshot timestamp format rather than introducing a parallel mechanism.
- **FR-012**: The feature MUST be verified by real Maven and Gradle clients resolving correctly after multi-version and multi-publisher publish scenarios (including a real round-trip), consistent with the project's existing real-client verification.

### Key Entities *(include if feature involves data)*

- **Artifact coordinate**: A groupId:artifactId whose set of versions lives under one path in a hosted repository; the unit for which artifact-level metadata is built.
- **Version**: A published version directory under an artifact, either a release or a `-SNAPSHOT`; the population enumerated to build the listing.
- **Snapshot build**: A timestamped/build-numbered file set within a `-SNAPSHOT` version (canonical `YYYYMMDD.HHMMSS-N` form); the population for resolving "newest snapshot build".
- **Artifact-level metadata**: The `maven-metadata.xml` (plus its checksum siblings) at `<groupPath>/<artifactId>/maven-metadata.xml` that advertises the version listing and latest/release/lastUpdated markers.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: After versions are published by two independent clients (each uploading only its own version's metadata), a consumer resolving the artifact sees 100% of the published versions — none are dropped from the listing.
- **SC-002**: "release" always equals the highest stored release version and "latest" the newest stored version after any sequence of publishes, verified by a real client.
- **SC-003**: A real Maven client and a real Gradle client both resolve a version range / "latest" and a snapshot dependency correctly against a multi-version, multi-publisher hosted repository.
- **SC-004**: Served metadata never fails a client's checksum verification (0 integrity errors across the test scenarios).
- **SC-005**: Proxy/remote and group repository metadata behavior is unchanged (existing proxy/group tests still pass; proxy metadata still served from upstream, never generated).
- **SC-006**: All pre-existing behavior and tests (publish/resolve/auth/cleanup) remain green; no other stored artifact is altered.

## Assumptions

- **Hybrid authoritative strategy** (per Clarifications): the server rebuilds metadata on publish (writing it + checksums to storage) and also computes it on read when the stored copy is missing/stale; the client still uploads its own `maven-metadata.xml` (clients unchanged), but the server's authoritative listing supersedes it.
- **Scope: artifact-level + version-level snapshot** (per Clarifications): the feature rebuilds the standard artifact-level `maven-metadata.xml` and the per-`-SNAPSHOT` version-level metadata. Group-level metadata is not part of standard Maven layout and is out of scope.
- **Snapshot format reuse**: the canonical `YYYYMMDD.HHMMSS-N` timestamp/build format already understood by the system is reused for both the artifact-level snapshot entries and the version-level snapshot listing.
- **Enumeration source of truth**: Stored content (the version directories present) is the source of truth for the listing, via the existing storage enumeration; the previously uploaded metadata is not trusted for the version set.
- **Hosted only**: Proxy/remote and group repositories are explicitly excluded; their metadata behavior is preserved.
- **No new client contract**: No change to how clients publish or resolve; this only makes the server's hosted metadata correct. No new dependency is anticipated (XML handling and enumeration already available).
- **Both storage backends**: Behavior is identical on filesystem and S3 (enumeration is backend-agnostic), so no backend-specific work is expected.
