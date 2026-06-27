<!--
SYNC IMPACT REPORT
Version change: (none) → 1.0.0
Bump rationale: Initial ratification of the Relikquary constitution. Adapted from the external
"Kontinuance" CI/CD constitution v1.1.0 baseline and retargeted for Relikquary's actual scope (a
Maven/Gradle artifact repository server). This starts a new version line at 1.0.0; it is NOT a
continuation of Kontinuance's versioning.
Principle changes vs. baseline:
  I.   Platform-First & Stable Public Contract → Repository Contract & Client Compatibility
       (reframed around Maven/Gradle repository layout + resolution contract and HTTP/config surface)
  II.  Test-First & Integration-Verified Discipline → retained and strengthened (real publish/
       resolve round-trips via Maven AND Gradle clients; real storage backends)
  III. Quality Gates Are Non-Negotiable → retained (detekt / Kover / SonarCloud)
  IV.  Correct, Covered Code Generation → REMOVED (Relikquary is not a code-generation tool; the
       inherited KSP/KotlinPoet scaffolding from the prior "Konstellation" project is out of scope)
  V.   Supply-Chain Integrity & Reproducible Publishing → renumbered to IV and broadened to
       "Supply-Chain Integrity & Faithful Storage" (Relikquary stores/serves OTHER projects'
       artifacts byte-for-byte, preserving their checksums and signatures)
Added sections: none beyond the retargeted core (4 principles)
Removed sections: none structurally; the code-generation principle was dropped
Templates reviewed:
  ✅ .specify/templates/plan-template.md   — "Constitution Check" gate is principle-agnostic
                                             ([Gates determined based on constitution file]); no edit needed
  ✅ .specify/templates/spec-template.md   — no constitution references; no edit needed
  ✅ .specify/templates/tasks-template.md  — no constitution references; no edit needed
Deferred / follow-up TODOs:
  - Module architecture not yet ratified: settings.gradle.kts references a `backend` module that
    does not exist on disk; the HTTP/protocol, storage-abstraction, and ingestion module split will
    be ratified as the architecture is decided (later amendment).
  - Concrete storage backends to support beyond filesystem (S3/DigitalOcean Spaces, etc.) to be
    confirmed during /speckit-plan.
-->

# Relikquary Constitution

Relikquary is an artifact repository server written in Kotlin and Spring Boot. It ingests artifact
publishes from Gradle — both standard `maven-publish` output (Maven layout: `groupId/artifactId/
version` with POM, jar, sources, javadoc, checksums, and optional signatures) and Gradle-native
output (Gradle Module Metadata `.module` files) — and serves them back over HTTP in a
Maven-compatible repository layout, so that any standard Maven or Gradle client pointed at the
correct endpoint can resolve and download them. Final storage is configurable to point at
different backends (e.g. local filesystem, S3 / DigitalOcean Spaces). These principles govern how
the project is changed, tested, and released, and will be refined as the architectural decisions
for module layout and storage backends are made.

## Core Principles

### I. Repository Contract & Client Compatibility

Relikquary's public contract is the repository it exposes, not its internal code. A published
artifact MUST remain resolvable by standard Maven and Gradle clients: the served repository layout
(path scheme, metadata files, `maven-metadata.xml`, checksum and Gradle Module Metadata
conventions) and the HTTP resolution/publish protocol MUST stay compatible with those clients. The
HTTP API and the configuration surface (storage targets, repository definitions) are likewise
public contracts. Any change that breaks layout, resolution, publish acceptance, or removes/renames
a configuration contract REQUIRES a MAJOR version bump. The `VERSION` file is the single source of
truth for the released version and MUST follow Semantic Versioning. Rationale: builds across many
projects pin Relikquary as a repository URL; silent incompatibility strands every consumer that
resolves through it.

### II. Test-First & Integration-Verified Discipline

Behavior changes MUST be accompanied by tests, written before the implementation where practical
(Red → Green → Refactor); new or changed public behavior MUST have a failing test demonstrated
before the fix lands. Because Relikquary's entire value is interoperability, correctness MUST be
proven by REAL round-trips, not mocks: a test MUST actually publish an artifact from a Gradle build
and then resolve that same artifact back through both a real Maven client and a real Gradle client.
Storage backends MUST each be exercised against their real boundary — Testcontainers for networked
backends (e.g. MinIO for the S3-compatible path), a real temporary directory for filesystem — wired
via `@SpringBootTest` + `DynamicPropertySource`, never by mocking the store away. Rationale: an
artifact repository that is not proven to round-trip with the actual toolchains cannot be trusted
to host anyone's builds.

### III. Quality Gates Are Non-Negotiable

The build's automated gates are the enforcement mechanism for these principles and MUST pass on
every change before merge:
- **detekt** static analysis MUST report zero violations (the build fails otherwise).
- **Kover** coverage verification MUST pass; code intentionally exempt from coverage MUST be
  annotated `@ExcludeFromCoverage` with justification rather than silently lowering thresholds.
- **SonarCloud** analysis MUST NOT be regressed by a change.
A gate MUST NOT be disabled, baselined, or weakened to make a change pass; fix the code instead, or
change the gate deliberately through the governance process. Rationale: gates only protect quality
if they cannot be bypassed under deadline pressure.

### IV. Supply-Chain Integrity & Faithful Storage

Relikquary is itself a supply-chain component: it stores and serves OTHER projects' artifacts, so it
MUST preserve stored bytes exactly and serve consumer-supplied checksums and signatures faithfully
— it MUST NEVER silently alter, re-checksum, or strip the signatures of stored artifacts. For
Relikquary's own build and releases: dependency verification (checksums and signatures in
`gradle/verification-metadata.xml`) MUST remain enabled, and new dependencies are added by
extending the verification metadata, never by disabling verification. Relikquary's own published
artifacts MUST be GPG-signed. Secrets (signing keys, storage-backend credentials) MUST be supplied
via environment variables or untracked local files and MUST NEVER be committed. Rationale: a
repository that corrupts or strips integrity metadata silently breaks the trust of every downstream
consumer that verifies what it pulls.

## Technology & Standards Constraints

- **Language/Toolchain**: Kotlin on the JDK 21 toolchain across all modules. Build logic is
  authored in Gradle Kotlin DSL (`*.gradle.kts`).
- **Runtime**: Relikquary runs on Spring Boot (Kotlin). The HTTP layer serves the Maven-compatible
  repository and accepts publishes; asynchronous coordination uses Kotlin coroutines; storage
  backends and their selection are wired through Spring configuration behind a pluggable storage
  abstraction, so the final storage location is configurable without code changes.
- **Integration testing**: integration tests use `@SpringBootTest` + Testcontainers and MUST NOT
  depend on shared external services or hard-coded ports (use `DynamicPropertySource`).
- **Module boundaries**: the system is split by responsibility (e.g. the HTTP / repository-protocol
  layer, the storage-backend abstraction and its implementations, and publish ingestion /
  validation). New modules MUST be registered through `settings.gradle.kts`. NOTE: the current
  `settings.gradle.kts` references a `backend` module that does not yet exist on disk; the concrete
  module layout will be ratified as the architecture is decided.
- **Dependencies**: declared through the Gradle version catalog (`gradle/libs.versions.toml`); a new
  or upgraded dependency MUST be reflected in `gradle/verification-metadata.xml`.
- **Conventions**: code MUST satisfy detekt's configured ruleset (package/class naming, trailing
  newline, etc.); template placeholder tokens MUST NOT remain in shipped sources.

## Development Workflow & Quality Gates

- Feature work follows Spec-Driven Development via Spec Kit: `/speckit-specify` →
  (`/speckit-clarify`) → `/speckit-plan` → `/speckit-tasks` → `/speckit-implement`, with
  `/speckit-analyze` and `/speckit-checklist` used to harden specs as needed.
- `./gradlew build` (compile + detekt + Kover verification + tests) MUST pass locally before a
  change is proposed for merge.
- A change that touches publish ingestion, repository serving, or a storage backend MUST include a
  passing integration test (`@SpringBootTest` + Testcontainers, with a real client round-trip where
  resolution or publishing behavior is affected); such a change without one MUST be rejected.
- Every change is reviewed; the reviewer MUST confirm the build is green and that these principles
  are upheld, justifying any exception in the PR.
- Publishing is a deliberate, signed release of the version named in `VERSION`; it MUST NOT happen
  as a side effect of routine development.

## Governance

This constitution supersedes ad-hoc practice. When a principle and convenience conflict, the
principle wins or the constitution is amended — it is not quietly ignored.

- **Amendments**: proposed via PR that edits this file, states the rationale, and bumps the version
  below per Semantic Versioning — MAJOR for principle removal/redefinition, MINOR for a new
  principle or materially expanded guidance, PATCH for clarifications.
- **Versioning policy**: the constitution version is independent of the application `VERSION`.
- **Compliance review**: every PR review verifies the change complies with these principles;
  unjustified gate bypasses are grounds to reject. Use `CLAUDE.md` and the active plan for
  day-to-day runtime guidance.

**Version**: 1.0.0 | **Ratified**: 2026-06-26 | **Last Amended**: 2026-06-26
