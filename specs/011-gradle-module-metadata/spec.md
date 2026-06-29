# Feature Specification: Gradle Module Metadata & Gradle-First Browsing

**Feature Branch**: `claude/spec-011-gradle-module-metadata` (feature directory `specs/011-gradle-module-metadata`)

**Created**: 2026-06-29

**Status**: Draft

**Input**: User description: "Make Gradle a first-class client of Relikquary with full Gradle Module
Metadata (GMM) fidelity, and give the browse UI design that surfaces Gradle modules. The `.module` files
Gradle publishes (variants, capabilities, attributes, dependency constraints) must be stored/served
faithfully and recognized as a distinct artifact kind; a real Gradle publish→consume round-trip with
variant selection must work on hosted and proxy repos; the browse UI must badge Gradle modules, offer
copy-paste consume snippets for Gradle and Maven, and present a module detail view (variants, their
attributes/capabilities, dependencies, and files). Maven contract and existing publish/resolve/auth are
unchanged; both storage backends behave identically. Out of scope: generating/rewriting module metadata,
server-side variant selection, validating `.module` against the jar."

## Clarifications

### Session 2026-06-29

- Q: Where is the Gradle Module Metadata parsed for the UI's variant detail view? → A: The **backend**
  parses the `.module` and exposes a structured response (variants, attributes/capabilities, dependencies,
  files) through the browse API; the frontend renders it. The stored `.module` bytes are never altered.
- Q: How is a `.module` recognized as Gradle Module Metadata? → A: By the `.module` extension **and** a
  filename that matches the coordinate (`{artifact}-{version}.module`) in that version directory — not any
  arbitrary `.module` file.
- Q: What form should the copy-paste consume snippets take? → A: **Both** Gradle DSLs (Kotlin and Groovy)
  **and** Maven XML — a Kotlin-DSL snippet, a Groovy-DSL snippet, and a Maven `<dependency>`/`<repository>`
  snippet, each pointing at this repository's URL.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Publish & resolve Gradle modules faithfully on a hosted repo (Priority: P1)

A team publishes a library from Gradle with Gradle Module Metadata enabled (so the build produces a
`.module` file alongside the POM and jar, describing the library's variants — e.g. API vs runtime — their
attributes, capabilities, and dependencies). They publish to a hosted Relikquary repository, and a
separate Gradle build consumes that library, relying on the module metadata to select the right variant.
Everything resolves and the variant model is preserved exactly as published.

**Why this priority**: This is the core of "Gradle as a first-class client." Without faithful module
metadata, Gradle silently falls back to POM-only resolution and the richer variant/capability model
(the whole reason Gradle publishes `.module`) is lost. Proving the round-trip is the foundation
everything else builds on.

**Independent Test**: Run a real Gradle build that publishes a library with module metadata to a hosted
repo, then a real consuming Gradle build that resolves it via the module metadata; confirm the resolved
files are byte-for-byte identical and the `.module` drove variant selection (the consumer gets the
variant's declared dependencies/files, not a POM fallback).

**Acceptance Scenarios**:

1. **Given** a Gradle build that publishes a module (`.module`, POM, jar, and their checksums/signatures),
   **When** it publishes to a hosted Relikquary repository, **Then** every file — including the `.module`
   and its sidecars — is accepted and stored byte-for-byte.
2. **Given** a published Gradle module, **When** a separate Gradle build resolves the dependency, **Then**
   Gradle fetches and uses the `.module` to select a variant, and the resolved artifacts match what was
   published, byte-for-byte.
3. **Given** an already-published release version's `.module`, **When** the same release coordinate is
   re-published, **Then** it is rejected as immutable (like the jar/POM); **and given** a snapshot
   version, re-publishing its `.module` is accepted (snapshot mutability), consistent with existing
   release/snapshot rules.
4. **Given** a Maven client that does not understand `.module`, **When** it resolves the same coordinate,
   **Then** it continues to work exactly as before (POM-based), unaffected by the module metadata.

---

### User Story 2 - Resolve Gradle modules through a proxy repository (Priority: P2)

A team consumes a Gradle library that lives on an upstream repository, through a Relikquary proxy. The
proxy fetches and caches the module metadata along with the artifacts, and serves the cached copy on
later requests, so Gradle's variant-aware resolution works through the proxy and stays consistent.

**Why this priority**: Proxying is how teams consume third-party Gradle libraries (many modern libraries
publish module metadata). The proxy must treat a versioned `.module` as the immutable, cacheable artifact
it is — not as a volatile listing — so cached resolution is correct and fast.

**Independent Test**: Point a Relikquary proxy at an upstream that serves a Gradle module; run a consuming
Gradle build through the proxy; confirm the `.module` and artifacts are fetched, cached, and that a second
resolve (cache hit) serves the same bytes and still drives variant selection.

**Acceptance Scenarios**:

1. **Given** a proxy whose upstream serves a Gradle module, **When** a Gradle build resolves the dependency
   the first time, **Then** the `.module` and its artifacts are fetched from upstream and cached.
2. **Given** the module was cached, **When** another Gradle build resolves it, **Then** it is served from
   cache (no upstream fetch needed) and variant selection still succeeds with byte-identical results.

---

### User Story 3 - Browse UI surfaces Gradle modules (Priority: P3)

An operator browsing a repository in the web UI can immediately see which artifacts are Gradle modules,
copy a ready-to-use dependency snippet for either Gradle or Maven, and open a module to understand its
variants — what each variant requires and produces — without downloading and reading raw files.

**Why this priority**: Makes the Gradle support discoverable and usable from the UI. It is valuable but
depends on the backend fidelity (US1) being in place first; the repository still functions for clients
without it.

**Independent Test**: Browse to a coordinate that has a `.module`; confirm it is visibly marked as a
Gradle module, that copy-paste consume snippets for Gradle and Maven are shown (with the correct
coordinate and this repository's URL), and that opening the module shows its variants with their
attributes/capabilities, dependencies, and files.

**Acceptance Scenarios**:

1. **Given** a coordinate with a `.module` present, **When** it is shown in the browse UI, **Then** it
   carries a clear "Gradle module" indicator that coordinates without a `.module` do not.
2. **Given** any artifact coordinate, **When** the operator views it, **Then** the UI offers copy-paste
   "consume" snippets for both Gradle and Maven that name the coordinate and this repository's URL.
3. **Given** a Gradle module, **When** the operator opens its detail view, **Then** the UI lists the
   module's variants and, for each, its attributes/capabilities, its dependencies, and the files it
   produces — derived from the module metadata.
4. **Given** a module metadata file that cannot be parsed or is malformed, **When** the operator opens it,
   **Then** the UI degrades gracefully (still browsable/downloadable) rather than erroring out.

### Edge Cases

- **Maven-only coordinate**: a coordinate with no `.module` (published by plain Maven) shows no Gradle
  badge and no module detail view, but still offers a Maven consume snippet (and a Gradle one) and browses
  normally.
- **Checksums/signatures**: a `.module`'s sidecars (`.module.sha256`, `.module.asc`, etc.) are stored and
  served like any other sidecar; the server never recomputes or alters them.
- **Snapshot modules**: a snapshot's `.module` is overwritable across builds (snapshot semantics), while a
  release version's `.module` is immutable.
- **Proxy upstream lacking a `.module`**: if the upstream has only a POM (no module metadata), proxied
  resolution still works (POM fallback) and no module is fabricated.
- **Malformed module metadata**: never blocks browsing/downloading; the detail view degrades gracefully.
- **Backend parity**: publish, resolve, proxy-cache, and the module detail view behave identically on the
  filesystem and S3 backends.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST accept and store Gradle Module Metadata (`.module`) files and their
  checksum/signature sidecars byte-for-byte on publish, exactly as it does for jar/POM files.
- **FR-002**: The system MUST recognize a `.module` file as a distinct Gradle module-metadata artifact
  kind when its name matches the coordinate (`{artifact}-{version}.module`) in that version directory (the
  `.module` extension alone is not sufficient), so publish acceptance, proxy caching, and the browse UI
  treat it appropriately, without changing how any existing file type is handled.
- **FR-003**: Publish acceptance for a `.module` MUST follow the same release/snapshot rules as the
  coordinate's other files: immutable for a release version (re-publish rejected), overwritable for a
  snapshot version.
- **FR-004**: A real Gradle build publishing a module (with variants/capabilities) MUST round-trip
  through a hosted repository and resolve in a separate real Gradle build via the module metadata, with
  variant selection succeeding and resolved files byte-for-byte identical to what was published.
- **FR-005**: Proxy resolution MUST fetch and cache a versioned `.module` as an immutable artifact (like
  the jar/POM), and serve the cached copy on subsequent requests — it MUST NOT treat a `.module` as a
  volatile pass-through listing.
- **FR-006**: The Maven client contract and existing publish/resolve/authorization behavior MUST be
  unchanged: Maven clients that ignore `.module` resolve exactly as before, and authorization rules apply
  to `.module` files as to any other file.
- **FR-007**: The browse UI MUST visibly mark a coordinate that has a `.module` as a Gradle module,
  distinguishing it from coordinates that do not.
- **FR-008**: The browse UI MUST present, for an artifact coordinate, copy-paste "consume" snippets in
  three forms — Gradle Kotlin DSL, Gradle Groovy DSL, and Maven XML — each naming the coordinate and this
  repository's resolvable URL.
- **FR-009**: For a Gradle module, the backend MUST parse the `.module` and expose its variants and, per
  variant, attributes/capabilities, dependencies, and produced files through the browse API as a
  structured response; the browse UI MUST render that as a module detail view. Parsing MUST NOT alter the
  stored bytes, and malformed metadata MUST degrade gracefully (the coordinate stays browsable and
  downloadable) without breaking browsing.
- **FR-010**: Gradle module handling (publish, resolve, proxy cache, and the UI module view) MUST behave
  consistently across the filesystem and S3 storage backends.
- **FR-011**: The system MUST NOT generate, rewrite, re-checksum, or validate `.module` contents (e.g.
  against the jar), nor perform server-side variant selection — it faithfully stores and serves what
  clients publish.

### Key Entities

- **Gradle Module Metadata**: the `.module` file a Gradle build publishes for a coordinate; a structured
  description of the module's variants. Stored as an opaque, byte-faithful artifact; parsed only to
  present the read-only detail view.
- **Variant**: a named consumable view of a module (e.g. API vs runtime) with attributes, capabilities,
  dependencies, and produced files — surfaced in the UI detail view.
- **Artifact coordinate**: a `group:artifact:version` location that may have a jar, POM, `.module`, and
  sidecars; the unit a consume snippet and the module detail view are built around.
- **Consume snippet**: a copy-paste dependency declaration (Gradle and Maven forms) plus this
  repository's URL, letting a user point a build at this repository and depend on the coordinate.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A real Gradle build publishes a module and a separate real Gradle build resolves it via the
  module metadata, with variant selection succeeding and resolved files byte-for-byte identical (hosted).
- **SC-002**: The same Gradle publish→consume round-trip succeeds when resolving through a proxy
  repository, with the second resolve served from cache and still byte-identical.
- **SC-003**: A release version's `.module` is immutable (re-publish rejected) while a snapshot version's
  `.module` is overwritable, matching the coordinate's other files.
- **SC-004**: Existing Maven and Gradle (POM-based) resolution round-trips continue to pass unchanged, and
  authorization behaves identically for `.module` files.
- **SC-005**: In the browse UI, a coordinate with a `.module` is shown as a Gradle module and one without
  is not; both offer correct Gradle and Maven consume snippets.
- **SC-006**: For a Gradle module, the UI detail view lists each variant with its attributes/capabilities,
  dependencies, and files; a malformed `.module` still lets the coordinate be browsed and downloaded.
- **SC-007**: All of the above hold identically on the filesystem and S3 backends.

## Assumptions

- **Module file naming**: Gradle Module Metadata follows Gradle's convention — an
  `{artifact}-{version}.module` JSON file in the same version directory as the POM/jar; recognition
  requires the coordinate-matching filename, not merely the `.module` extension (resolved in
  clarification).
- **Detail-view data source**: the backend parses the published `.module` JSON and exposes the
  variant/dependency/file information as a structured browse-API response; the frontend renders it. The
  server never alters the stored bytes (resolved in clarification).
- **Consume-snippet form**: the UI renders three snippets — Gradle Kotlin DSL (`implementation("g:a:v")` +
  a `maven { url }` repository block), Gradle Groovy DSL (`implementation 'g:a:v'` + repository block), and
  Maven `<dependency>` + `<repository>` — each pointing at this repository's URL (resolved in
  clarification).
- **Round-trip test fixtures**: the Gradle round-trip enables Gradle Module Metadata publication and uses
  a variant/capability that exercises module-metadata-driven selection, run with real Gradle clients
  (consistent with the constitution's real-round-trip requirement). Docker-gated paths follow existing
  practice.
- **No new storage capabilities**: faithful byte storage already exists; this feature adds recognition,
  proxy-cache correctness for `.module`, browse/parse presentation, and tests — it does not change the
  storage contract.
- **Authorization unchanged**: `.module` files are governed by the same per-repository and global rules as
  any other file; no new permissions are introduced.
