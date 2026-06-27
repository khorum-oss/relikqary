# Implementation Plan: Core Publish-and-Resolve MVP

**Feature**: `001-publish-resolve-mvp` (tracked via `.specify/feature.json`; developed on git branch `claude/ecstatic-cerf-5ohbct`) | **Date**: 2026-06-26 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/001-publish-resolve-mvp/spec.md`

## Summary

Deliver the smallest end-to-end slice of Relikquary: a Spring Boot (Kotlin) service that accepts a
Gradle `maven-publish` upload over HTTP, stores every file byte-for-byte in a configurable filesystem
location behind a storage abstraction, and serves the files back at standard Maven-repository-layout
paths so that unmodified Maven AND Gradle clients resolve the artifact. Re-publish is governed by a
configurable policy defaulting to standard Maven semantics (release immutable → 409, SNAPSHOT
overwritable); checksum sidecars are stored as received (faithful storage). Correctness is proven by
a real publish→resolve round-trip integration test driving genuine Gradle and Maven clients.

## Technical Context

**Language/Version**: Kotlin 2.1.20 on the JDK 21 toolchain (bumped from the inherited 17 per
ratified constitution; see Constitution Check).

**Primary Dependencies**: Spring Boot 4.1.x (latest stable 4.x line as of 2026-06; requires Java 17+,
compatible through Java 26, so JDK 21 is supported) — Spring Web MVC for the HTTP repository protocol;
Kotlin coroutines available where concurrency helps; Jackson/Kotlin for any JSON config. Build via
Gradle Kotlin DSL with the version catalog (`gradle/libs.versions.toml`).

**Storage**: Configurable filesystem location behind an `ArtifactStorage` abstraction
(`FilesystemArtifactStorage` for this MVP). Object backends (S3/Spaces) are out of scope but the
abstraction is designed not to preclude them.

**Testing**: JUnit Jupiter + Kotlin test; MockK for unit-level isolation; `@SpringBootTest`
(RANDOM_PORT) for integration. Real-client round-trip driven by Gradle TestKit (`GradleRunner`) for
publish + Gradle-resolve and Apache Maven (maven-invoker / a pinned Maven distribution) for
Maven-resolve. Testcontainers is the mandated tool for *networked* backends; for the filesystem
backend the real boundary is a JUnit `@TempDir`. (See research.md for the round-trip harness
decision.)

**Target Platform**: Linux JVM server (containerizable Spring Boot app).

**Project Type**: Web service (single Spring Boot backend module).

**Performance Goals**: MVP correctness-first; no hard throughput target. Streams artifact bytes
rather than buffering whole files in memory (constraint below).

**Constraints**: Files MUST be served byte-for-byte identical to upload (FR-003); large artifacts
MUST stream (no full-file in-memory buffering); not-found MUST be a clean 404 so clients fall through
to other repositories (FR-008).

**Scale/Scope**: Single implicit repository, single instance, no auth, no UI. One vertical slice.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Evaluated against `.specify/memory/constitution.md` v1.0.0:

- **I. Repository Contract & Client Compatibility** — PASS. The plan serves the standard Maven
  repository layout and is validated against real Maven + Gradle clients (FR-004, FR-011). No public
  contract is broken (greenfield).
- **II. Test-First & Integration-Verified** — PASS. The plan mandates a real publish→resolve
  round-trip integration test (Gradle publish, then Maven AND Gradle resolve, byte-for-byte compare),
  with tests written before/with implementation. Filesystem backend exercised against a real `@TempDir`
  boundary, not mocked.
- **III. Quality Gates Are Non-Negotiable** — PASS (with required build cleanup). detekt/Kover/Sonar
  remain enforced. The inherited `build.gradle.kts` has stale `project(":dsl")` references and Sonar
  paths that would break the build; cleaning these to the realized `backend` module is part of this
  work, not a gate bypass.
- **IV. Supply-Chain Integrity & Faithful Storage** — PASS. Faithful byte-for-byte storage is FR-003.
  Dependency verification (`org.gradle.dependency.verification`) is being turned **on** and
  `gradle/verification-metadata.xml` regenerated for the new Spring/test deps (operator decision), so
  the inherited `verification=off` is corrected rather than carried as a violation.

**Inherited-build reconciliations (resolved decisions, not violations):** JDK 17→21; dependency
verification off→on (regenerate metadata); remove Micronaut version property, KSP2 flag, custom
`khorum.*` plugin aliases and `:dsl` references left over from Konstellation; realize the `backend`
module referenced by `settings.gradle.kts`.

**Result:** No unjustified violations. Complexity Tracking is empty.

## Project Structure

### Documentation (this feature)

```text
specs/001-publish-resolve-mvp/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output (repository HTTP protocol)
│   └── repository-http.md
└── checklists/
    └── requirements.md  # from /speckit-specify
```

### Source Code (repository root)

Single Spring Boot `backend` module (decision: start single-module, refactor to multi-module when a
second storage backend justifies it). Internal package boundaries mirror the constitution's
responsibility split.

```text
backend/
├── build.gradle.kts
└── src/
    ├── main/kotlin/org/khorum/oss/relikquary/
    │   ├── RelikquaryApplication.kt          # Spring Boot entrypoint
    │   ├── protocol/                         # HTTP layer: Maven repo GET/PUT/HEAD controller
    │   ├── ingestion/                        # publish acceptance + republish-policy enforcement
    │   ├── storage/                          # ArtifactStorage abstraction + FilesystemArtifactStorage
    │   ├── coordinate/                       # coordinate/path parsing & Maven layout model
    │   └── config/                           # @ConfigurationProperties (storage location, policy)
    └── test/kotlin/org/khorum/oss/relikquary/
        ├── unit/                             # coordinate parsing, policy, storage (MockK/@TempDir)
        └── integration/                      # @SpringBootTest round-trip (Gradle publish + M/G resolve)
```

**Structure Decision**: Single `backend` Spring Boot module realized under `settings.gradle.kts`'s
existing `includeModules("backend")`. Packages `protocol`, `ingestion`, `storage`, `coordinate`,
`config` enforce the responsibility split internally; a later amendment may promote `storage` to its
own Gradle module when S3/Spaces is added.

## Complexity Tracking

> No constitution violations require justification. Section intentionally empty.
