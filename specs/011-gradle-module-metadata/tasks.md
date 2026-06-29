---
description: "Task list for Gradle Module Metadata & Gradle-first browsing"
---

# Tasks: Gradle Module Metadata & Gradle-First Browsing

**Input**: `specs/011-gradle-module-metadata/`. **Tests**: included (Constitution Principle II — real
Gradle round trips). Backend paths under `backend/src/.../org/khorum/oss/relikquary/`, tests under
`backend/src/test/...`; frontend under `frontend/src/`. **No new dependencies** (Jackson already present)
→ `verification-metadata.xml` untouched.

## Phase 1: Setup

- [ ] T001 Confirm no new dependencies are needed (Jackson `jackson-module-kotlin` is already on the
  classpath for GMM parsing) and create the backend `gradle/` package directory
  (`backend/src/main/kotlin/org/khorum/oss/relikquary/gradle/`).

## Phase 2: Foundational (blocking prerequisites for all stories)

- [ ] T002 Add coordinate accessors and recognition to `coordinate/RepositoryPath.kt`: `artifactId` (the
  segment above the version directory), `version` (the version-directory segment), and
  `isModuleMetadata()` (file name ends with `.module` AND starts with `"{artifactId}-"`). Leave
  `classify()` unchanged so the existing release-immutable / snapshot-overwritable behaviour is preserved.
- [ ] T003 [P] Unit test `unit/RepositoryPathModuleTest.kt`: `isModuleMetadata()` true for a release
  `widget-1.2.3.module` and a timestamped snapshot `widget-1.0-…-1.module`; false for a non-matching
  `other-1.0.module`, a `maven-metadata.xml`, and a `.jar`/`.pom`; assert `classify()` still returns
  `RELEASE` for a release `.module` and `SNAPSHOT` for a snapshot `.module` (locks the immutability inputs).

**Checkpoint**: a `.module` is recognized as a distinct artifact kind, and the already-correct
immutability/classification inputs are pinned by tests.

## Phase 3: User Story 1 — Gradle modules round-trip faithfully on a hosted repo (Priority: P1)

**Goal**: A real Gradle build that publishes Gradle Module Metadata (with a feature variant/capability)
round-trips through a hosted repo and resolves in a separate real Gradle build via the `.module`.

**Independent test**: Publish a feature-variant library to a hosted repo; a consumer that requires the
capability resolves it (only possible via the `.module`); resolved files are byte-for-byte identical.

- [ ] T004 [US1] Real Gradle round-trip `integration/GradleModuleRoundTripTest.kt` (external-process Gradle
  harness, mirroring `PublishResolveRoundTripTest`): a publisher `java-library` that registers a feature
  with a capability (`java { registerFeature("extra") { usingSourceSet(sourceSets["main"]) } }`) publishes
  to a hosted `releases` repo (producing `.module`, POM, jars + sidecars); a separate consumer build
  depends on the coordinate `requireCapability("{group}:{artifact}-extra")` and resolves it; assert the
  resolved artifact is byte-for-byte identical to the published bytes (resolution is only possible via the
  `.module`, proving GMM fidelity — FR-004, SC-001).
- [ ] T005 [US1] Integration test `integration/ModuleImmutabilityTest.kt` (`@SpringBootTest(RANDOM_PORT)` +
  `HttpClient`): PUT a release coordinate's `.module` to a `release` repo twice → `201` then `409`
  (immutable); PUT a snapshot coordinate's `.module` to a `snapshot` repo twice → `201`/`200` (overwritable)
  (FR-003, SC-003); a GET returns the stored `.module` bytes unchanged.

**Checkpoint**: the hosted Gradle publish→consume round-trip with variant selection passes; `.module`
immutability matches the coordinate's other files.

## Phase 4: User Story 2 — Gradle modules resolve through a proxy (Priority: P2)

**Goal**: A proxy caches a versioned `.module` (like the jar/POM) and re-serves it, so variant-aware
resolution works through the proxy.

**Independent test**: Resolve the feature-variant capability through a proxy whose upstream serves the
module; the first resolve caches it and a second resolve (cache hit) is byte-identical and still selects
the variant.

- [ ] T006 [US2] Integration test `integration/GradleModuleProxyRoundTripTest.kt`: publish the
  feature-variant library to a hosted `releases` repo, configure a `proxy` repo whose `remoteUrl` is that
  hosted repo (proxy-of-self) — or a stub upstream seeded with the published files; run a consumer build
  that requires the capability through the proxy; assert the `.module` and artifacts are fetched and
  cached on the first resolve, a second resolve serves from cache (no upstream fetch) and is byte-identical
  with variant selection intact (FR-005, SC-002).

**Checkpoint**: proxied Gradle module resolution is correct and cache-served.

## Phase 5: User Story 3 — Browse UI surfaces Gradle modules (Priority: P3)

**Goal**: The browse UI badges Gradle modules, offers Gradle (Kotlin + Groovy) and Maven consume
snippets, and renders a module detail view from the backend-parsed GMM.

**Independent test**: Browse a coordinate with a `.module`; it is badged, shows all three consume
snippets with the correct coordinate + repo URL, and a detail view lists each variant's attributes,
capabilities, dependencies, and files; a malformed `.module` still browses/downloads.

- [ ] T007 [US3] Add `gradle/GradleModuleMetadata.kt`: the parsed model — `GradleModuleMetadata`
  (`formatVersion`, `Component`, `variants`), `Variant` (`name`, `attributes: Map<String,String>`,
  `capabilities`, `dependencies`, `files`), `Capability`, `Dependency`, `ModuleFile`, and a `ParseResult`
  sealed type (`Parsed`/`Unparseable`).
- [ ] T008 [US3] Add `gradle/GradleModuleMetadataParser.kt`: parse `.module` JSON with Jackson into
  `ParseResult`; ignore unknown fields (`FAIL_ON_UNKNOWN_PROPERTIES=false`); coerce attribute values to
  strings; never throw — malformed/empty input returns `Unparseable(reason)`.
- [ ] T009 [P] [US3] Unit test `unit/GradleModuleMetadataParserTest.kt`: a well-formed `.module` parses to
  variants with attributes/capabilities/dependencies/files; an unknown extra field is tolerated; malformed
  JSON and empty bytes return `Unparseable` (graceful, no exception).
- [ ] T010 [US3] Extend `protocol/dto/BrowseDtos.kt`: add `Coordinate(group, artifact, version)`,
  `ModuleRef(path)`, optional `coordinate`/`module` on `ContentsResponse`, and `ModuleMetadataResponse`
  (`repository`, `path`, `parseable`, `component?`, `variants`).
- [ ] T011 [US3] Extend `protocol/BrowseController.kt`: in `contents`, when the path is a coordinate's
  version directory, populate `coordinate` and (when a recognized `.module` is present via
  `isModuleMetadata()`) `module`; add `GET /api/repositories/{repo}/module/**` that reads the `.module`
  bytes, parses, and returns `ModuleMetadataResponse` — `404` when the path is not a recognized module, a
  graceful `parseable:false` body (HTTP 200) when present-but-unparseable.
- [ ] T012 [US3] Gate the new sub-resource in `security/RepositoryAuthorizationManager.kt`: in
  `browseTarget`, map a `GET` on the `module` sub-resource to `Action.READ` (like `contents`/`file`), so a
  private repo's module endpoint is read-gated (`401`/`403`) rather than open.
- [ ] T013 [US3] Integration test `integration/ModuleBrowseApiTest.kt`: `contents` of a module coordinate
  returns `coordinate` + `module` (and nulls for a Maven-only coordinate); `/module/**` returns parsed
  variants; a malformed `.module` returns `parseable:false` at HTTP 200 and the coordinate still
  lists/downloads; on a private repo `/module` returns `401` anon, `403` non-reader, `200` reader (FR-009,
  FR-006, SC-005/SC-006).
- [ ] T014 [P] [US3] Extend `frontend/src/lib/api.ts`: add `Coordinate`, `ModuleRef`, `ModuleMetadata`
  (with `Variant`/`Capability`/`Dependency`/`ModuleFile`) types, the `coordinate`/`module` fields on
  `ContentsResponse`, and `moduleMetadata(repo, path)`.
- [ ] T015 [US3] Add `frontend/src/lib/components/GradleModuleBadge.svelte` and
  `frontend/src/lib/components/ConsumeSnippets.svelte` — the badge shown when a coordinate has a module;
  the snippets render Gradle Kotlin DSL, Gradle Groovy DSL, and Maven XML (tab/toggle + copy), built from
  the `coordinate` and the repository URL (page origin + `/{repo}`).
- [ ] T016 [US3] Add `frontend/src/lib/components/ModuleDetail.svelte`: fetch `moduleMetadata` and list each
  variant with its attributes, capabilities, dependencies, and files; render a graceful notice when
  `parseable:false`.
- [ ] T017 [US3] Wire into `frontend/src/routes/r/[repo]/[...path]/+page.svelte`: when `contents.coordinate`
  is present show `ConsumeSnippets`; when `contents.module` is present show `GradleModuleBadge` and a
  "view module" affordance that loads and renders `ModuleDetail`.
- [ ] T018 [US3] Frontend test under `frontend/tests/` (Playwright/e2e, seeded backend): a coordinate with a
  `.module` shows the Gradle badge; the consume snippets render all three forms with the correct coordinate
  and repository URL; the module detail view lists the variants.

**Checkpoint**: operators can see, copy-consume, and inspect Gradle modules from the browse UI.

## Phase 6: Polish & verify

- [ ] T019 [P] Document Gradle support in `README.md`: publishing/consuming with Gradle Module Metadata,
  faithful `.module` storage (release-immutable/snapshot-overwritable, proxy-cached), the module browse
  endpoint, the consume snippets, and the module detail view.
- [ ] T020 `./gradlew build` green (detekt zero + Kover + unit/integration incl. the Gradle round-trips and
  module browse + authz; existing Maven/Gradle POM round-trips unchanged; `verification-metadata.xml`
  untouched) AND `cd frontend && npm run build && npm test` green; commit & push to
  `claude/spec-011-gradle-module-metadata` (updates PR #15).

## Dependencies

Setup (T001) → Foundational (T002–T003) precede the stories. **US1** (T004–T005) needs no production
change beyond recognition; it proves and pins existing behaviour. **US2** (T006) reuses US1's round-trip
fixture. **US3** backend: T007→T008→T009; T010→T011 (T011 uses recognition T002 + the parser); T012;
T013 depends on T010–T012. **US3** frontend: T014→{T015,T016}→T017→T018. Polish (T019–T020) last.
US1/US2 and US3 are otherwise independent (US3 needs no round-trip; US1/US2 need no parser/UI).

## Parallel opportunities

- T003 ∥ later work (recognition test). T009 (parser unit) ∥ T013 (browse integration) once their inputs
  exist. T014 (frontend types) ∥ backend US3 tasks. T015 ∥ T016 (different components). T019 ∥ tests.
- Cross-story: US3 can proceed in parallel with US1/US2 (no shared production files except the additive
  browse layer, which US1/US2 don't touch).

## MVP scope

Setup + Foundational + **US1** (hosted Gradle module round-trip) is the MVP — it proves Gradle works
correctly as a first-class client (the core request). **US2** (proxy) extends it to third-party consumption;
**US3** (browse UI: badge, consume snippets, module detail) makes Gradle modules visible and usable.
