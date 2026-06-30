# Implementation Plan: Server-Side maven-metadata.xml for Hosted Repositories

**Branch**: `claude/spec-014-hosted-maven-metadata` | **Date**: 2026-06-30 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/014-hosted-maven-metadata/spec.md`

## Summary

Make Relikquary authoritative for **hosted** `maven-metadata.xml`. Today the server stores the client's
uploaded metadata verbatim, so a second independent publisher's upload erases the first's versions. This
feature adds a `HostedMetadataService` that, **on each publish of a hosted coordinate file**, enumerates the
versions actually in storage (`ArtifactStorage.list`/`walk`) and (re)writes the authoritative artifact-level
`maven-metadata.xml` — plus, for `-SNAPSHOT` versions, the version-level snapshot metadata (`<snapshotVersions>`
with the canonical `YYYYMMDD.HHMMSS-N` timestamps already understood by cleanup) — each with consistent
`.sha1`/`.md5` siblings. Per the **hybrid** clarify decision, reads also **compute-on-read** when the stored
metadata is missing (fallback), so a listing is never absent. Generation is serialized per coordinate (an
in-process striped lock) so concurrent publishes converge to the full version set. Proxy/remote and group
metadata are untouched (pass-through preserved). No new dependency — XML via the JDK StAX writer, checksums
via `java.security.MessageDigest`. Verified by real Maven + Gradle multi-version / multi-publisher round-trips.

## Technical Context

**Language/Version**: Kotlin 2.3.21 / JDK 21.

**Primary Dependencies**: Spring Boot 4.1.0. **No new dependency** — `javax.xml.stream` (StAX) for metadata
XML, `java.security.MessageDigest` for `.sha1`/`.md5`, and the existing `ArtifactStorage` enumeration.

**Storage**: Existing `ArtifactStorage` (filesystem / S3). Uses `list(prefix)` (immediate children → version
dirs) and `walk(prefix)` (recursive → files + lastModified for latest/release/lastUpdated and snapshot
builds). Generated metadata is written via `write(key, …)` (atomic temp-file move on filesystem). Backend-agnostic.

**Testing**: JUnit 5 + `@SpringBootTest`; unit tests for the XML builder and version/snapshot enumeration;
integration tests simulating two independent publishers (each PUTing only its own version + its own
metadata) and asserting the served listing contains all versions with correct latest/release; a real
Maven + Gradle round-trip resolving a version range / latest and a snapshot across multi-publisher state
(reusing the external-process round-trip harness).

**Target Platform**: Linux server (Spring Boot fat jar).

**Project Type**: Web service (`backend/` module; `frontend/` unaffected).

**Performance Goals**: Regenerate-on-publish keeps reads cheap (serve the stored file). Enumeration is scoped
to one artifact directory (`{repo}/{group}/{artifactId}/`), not the whole repo. Compute-on-read only fires on
a missing metadata file.

**Constraints**: Existing publish/resolve/auth/proxy/group/cleanup behavior unchanged (only hosted metadata
becomes authoritative); strict dependency verification untouched (no new deps); detekt zero + Kover hold;
generation must not alter any other stored artifact (FR-008); concurrent publishes converge (FR-007).

**Scale/Scope**: One backend module. New `metadata/` package (~3 classes), a publish-path hook and a
hosted-metadata read hook in `RepositoryController`/resolver, reuse of the snapshot timestamp format. No
config surface change, no client change.

## Constitution Check

*GATE: re-checked after Phase 1 design — PASS.*

- **I. Repository Contract & Client Compatibility** — PASS. The served hosted metadata stays valid Maven
  repository-layout `maven-metadata.xml`; this makes it *more correct* (authoritative version listing) rather
  than changing the wire format or resolution protocol. Clients publish and resolve exactly as before; the
  server simply stops letting one publisher's upload clobber the listing. No layout/protocol break → no MAJOR
  bump. The change is confined to hosted repos; proxy/group are explicitly untouched (FR-006).
- **II. Test-First & Integration-Verified** — PASS. Correctness is proven by **real Maven and Gradle**
  round-trips over multi-version, multi-publisher state (the project's mandated real-client verification),
  plus unit tests for the XML/enumeration logic. No mocks substitute the resolution path.
- **III. Quality Gates** — PASS. New Kotlin obeys detekt/Kover; **no new dependency** (StAX + MessageDigest +
  existing storage) → `verification-metadata.xml` untouched.
- **IV. Supply-Chain Integrity & Faithful Storage** — PASS, reinforced. Generated metadata carries matching
  `.sha1`/`.md5` so client integrity checks pass (FR-003); **no other stored artifact is modified** (FR-008) —
  POMs, jars, sources, javadoc, checksums, signatures remain byte-for-byte as published.

No violations → Complexity Tracking not required.

## Project Structure

### Documentation (this feature)

```text
specs/014-hosted-maven-metadata/
├── plan.md              # This file
├── research.md          # Phase 0 decisions (D1–D8)
├── data-model.md        # Metadata structures, enumeration, latest/release/snapshot rules
├── quickstart.md        # Multi-publisher / snapshot validation scenarios
├── contracts/
│   └── metadata.md      # What the server generates/serves (artifact- & version-level), checksums, triggers
├── checklists/
│   └── requirements.md  # Spec quality checklist (specify + clarify)
└── tasks.md             # Phase 2 output (/speckit-tasks)
```

### Source Code (repository root)

```text
backend/src/main/kotlin/org/khorum/oss/relikquary/
├── metadata/
│   ├── MavenMetadata.kt          # NEW: data classes (ArtifactMetadata, SnapshotMetadata, version/build models)
│   ├── MavenMetadataXml.kt       # NEW: StAX serializer → byte-faithful maven-metadata.xml
│   └── HostedMetadataService.kt  # NEW @Service: enumerate storage, build + write metadata + checksums,
│                                 #   per-coordinate lock; regenerate(repo, path) + ensureForRead(repo, path)
├── protocol/
│   └── RepositoryController.kt   # MODIFY: after ACCEPT write of a hosted coordinate file → regenerate;
│                                 #   on GET of a hosted maven-metadata.xml that is missing → compute-on-read
└── coordinate/
    └── (reuse RepositoryPath: version, artifactId, classify; snapshot YYYYMMDD.HHMMSS-N format)

backend/src/test/kotlin/org/khorum/oss/relikquary/
├── metadata/
│   ├── MavenMetadataXmlTest.kt       # NEW unit: artifact- & version-level XML shape, latest/release/lastUpdated
│   └── HostedMetadataServiceTest.kt  # NEW unit: enumeration, multi-version listing, snapshot build selection
└── integration/
    ├── HostedMetadataHttpTest.kt     # NEW: two independent publishers → served listing has all versions + checksums
    └── HostedMetadataRoundTripTest.kt# NEW: real Maven + Gradle resolve range/latest + snapshot, multi-publisher
```

**Structure Decision**: A new `metadata/` package isolates generation (data model + serializer + service)
from the protocol layer; `RepositoryController` only gains two thin hooks (post-publish regenerate,
read-miss compute). Reuses `ArtifactStorage`, `RepositoryPath`, and the existing snapshot timestamp format.
`frontend/` untouched.

## Complexity Tracking

No constitutional violations. The one notable design point — concurrent-publish convergence — is handled by
an in-process per-coordinate lock plus the compute-on-read fallback; a multi-instance (shared-store,
multi-replica) deployment's cross-instance race is documented in research.md as a known limitation mitigated
by the next publish's full regeneration and the read-side fallback, not a silent gap.
