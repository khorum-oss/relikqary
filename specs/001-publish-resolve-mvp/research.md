# Phase 0 Research: Core Publish-and-Resolve MVP

This resolves the technical unknowns for the plan. Each item: Decision / Rationale / Alternatives.

## 1. Web framework: Spring Web MVC vs WebFlux

**Decision**: Spring Web MVC (servlet stack) on Spring Boot 4.1.x.

**Rationale**: The repository protocol is mostly streaming file GET/PUT/HEAD over HTTP — a domain MVC
handles simply with `StreamingResponseBody` / `InputStreamResource` and `HttpServletRequest`
input streams, with no full-file buffering. MVC keeps the MVP small and is the best-trodden path for
Gradle TestKit / Maven round-trip testing. The constitution's "coroutines where concurrency helps"
still applies to any internal async work, but does not require WebFlux.

**Alternatives**: WebFlux + coroutines (reactive streaming) — more moving parts and back-pressure
semantics that the MVP does not need; deferred. Plain servlet without Spring — loses Spring config /
`@ConfigurationProperties` ergonomics the storage-location requirement leans on.

## 2. Repository wire protocol (how Maven/Gradle publish & resolve)

**Decision**: Implement a single wildcard-path controller serving the standard Maven layout:
`GET`/`HEAD` for resolution and `PUT` for publishing at
`/{repo}/{groupId-as-path}/{artifactId}/{version}/{filename}` (plus the artifact-level
`maven-metadata.xml`). Map the whole subtree with an `**` path pattern and resolve the on-disk/storage
key from the request path 1:1.

**Rationale**: Both Gradle's `maven-publish` and Maven deploy use plain HTTP `PUT` to upload each file
(artifact, POM, checksums `.sha1/.md5/.sha256/.sha512`, signatures `.asc`, and `maven-metadata.xml`)
and `GET` to resolve, addressed purely by Maven-layout path. A path-passthrough design is therefore
fully client-compatible and keeps Relikqary a faithful byte store (FR-003, FR-004, FR-011).

**Alternatives**: A typed REST API with explicit coordinate fields — would not be what stock clients
speak; rejected. Server-side per-request transformation of bytes — violates faithful storage.

## 3. `maven-metadata.xml` (version discovery, FR-005)

**Decision**: For the MVP, **store and serve** the `maven-metadata.xml` that `maven-publish` uploads,
exactly as received. Do not synthesize or rewrite it server-side in this slice.

**Rationale**: Gradle/Maven publish the artifact-level `maven-metadata.xml` (and its checksums) as
part of the publish, so storing+serving it satisfies version discovery for the round-trip without a
metadata generator. This keeps the MVP within faithful-storage behavior.

**Verification guard**: because this decision *assumes* the client uploads `maven-metadata.xml`, the
publish integration test MUST assert that the metadata file actually arrives in storage after a real
`maven-publish`. If the chosen publish flow does not upload it, FR-005 version discovery has no source
and server-side synthesis must be promoted from follow-up into scope.

**Alternatives**: Server-side metadata regeneration/merge (compute `<versions>` from stored
coordinates) — more robust for concurrent/independent publishes and required once multiple publishers
or SNAPSHOT timestamping arrive; recorded as a **follow-up hardening task**, out of scope here.

## 4. Re-publish policy (FR-010)

**Decision**: A `RepublishPolicy` checked in the ingestion layer before a `PUT` is committed.
Classify the coordinate as SNAPSHOT (version ends with `-SNAPSHOT`) vs RELEASE. Default policy:
RELEASE that already exists → reject the `PUT` with HTTP 409 Conflict, leaving stored bytes
unchanged; SNAPSHOT → allow overwrite. The mode is operator-configurable via
`@ConfigurationProperties` (e.g. `relikqary.publish.release-policy = reject | overwrite`) with no
code change.

**Rationale**: Matches standard Maven semantics the user requested and upholds Principle I
(release immutability) while leaving an escape hatch for operators.

**Alternatives**: Idempotent-if-identical (compare bytes) — more work, deferred; global last-write-
wins — violates release immutability by default.

## 5. Checksum-sidecar handling (FR-009)

**Decision**: Store the artifact bytes and any checksum/signature sidecars exactly as received; do
**not** validate sidecar-vs-bytes on upload in this slice. Verification is left to the consuming
client on download.

**Rationale**: Faithful storage (Principle IV / FR-003). Standard clients upload artifact and
checksum as separate requests anyway, so server-side cross-validation is awkward and unnecessary for
the round-trip. The design leaves room for a future configurable strict-validation mode (FR-009a).

**Alternatives**: Reject-on-mismatch strict mode — recorded as future configurable behavior, not MVP.

## 6. Storage abstraction & configurable location (FR-002, FR-007)

**Decision**: An `ArtifactStorage` interface (`exists(key)`, `get(key): stream`, `put(key, stream)`,
keys = repository-layout relative paths) with a `FilesystemArtifactStorage` implementation rooted at a
configurable base directory. Bind the root via `@ConfigurationProperties("relikqary.storage")` (e.g.
`relikqary.storage.filesystem.root`). Writes are atomic (write to a temp file, then move) and stream
to avoid buffering whole files.

**Rationale**: Satisfies "point at different filesystem locations without code changes" and keeps the
door open for S3/Spaces (`S3ArtifactStorage`) behind the same interface without touching the protocol
or ingestion layers. Atomic move avoids serving half-written files (partial-artifact edge case).

**Alternatives**: Direct `java.nio` calls in the controller — couples protocol to storage, blocks the
future S3 backend; rejected.

## 7. Real-client round-trip verification harness (Principle II — the key decision)

**Decision**: `@SpringBootTest(webEnvironment = RANDOM_PORT)` boots Relikqary with its storage root
injected via `@DynamicPropertySource` pointing at a JUnit `@TempDir` (matching the constitution's
explicit `@SpringBootTest` + `DynamicPropertySource` wiring mandate — no hard-coded paths or ports).
The test then:
1. Generates a tiny throwaway library project and runs a **real** Gradle `maven-publish` against the
   app URL using Gradle TestKit (`GradleRunner`) — proves SC-001.
2. Resolves the just-published coordinate with a **real Gradle** consumer project via `GradleRunner`
   — proves SC-003.
3. Resolves the same coordinate with a **real Maven** client (Apache Maven driven by `maven-invoker`,
   or a pinned Maven distribution) — proves SC-002.
4. Compares content hashes of published vs resolved files — proves SC-004 / FR-003.

**Rationale**: The constitution demands real publish/resolve round-trips through the actual toolchain,
not mocks. GradleRunner and maven-invoker drive genuine clients in-process/sub-process without a
container. For the filesystem backend the "real boundary" is the `@TempDir` (per Principle II's
filesystem guidance); Testcontainers becomes the boundary tool when the S3 backend (MinIO) is added.

**Alternatives**: Testcontainers running gradle/maven CLI images — heavier and slower for the
filesystem MVP, reserved for the future S3 backend round-trip. Mocking client behavior — explicitly
forbidden by Principle II.

## 8. Inherited-build cleanup (reconciliations)

**Decision**: As part of this feature, bring the inherited Konstellation build in line: bump the JDK
toolchain 17→21; set `org.gradle.dependency.verification` on and regenerate
`gradle/verification-metadata.xml` for the new Spring/test dependencies; remove the leftover Micronaut
version property, the `ksp.useKSP2` flag, the custom `khorum.*` plugin aliases and `project(":dsl")`
references in `build.gradle.kts`/Sonar config; and realize the `backend` module that
`settings.gradle.kts` already includes.

**Rationale**: These are stale carry-overs that either contradict the constitution (JDK, verification)
or reference a non-existent code-gen module; leaving them breaks `./gradlew build` (Principle III).

**Alternatives**: Defer cleanup — rejected; the build will not go green without it.

---

**Sources** (Spring Boot current-version check, 2026-06):
- [Spring Boot — endoflife.date](https://endoflife.date/spring-boot)
- [HeroDevs — Spring Boot Versions, EOL Dates, and Latest Releases (April 2026)](https://www.herodevs.com/blog-posts/spring-boot-versions-eol-dates-and-latest-releases-april-2026)
- [Spring Boot project page](https://spring.io/projects/spring-boot/)
