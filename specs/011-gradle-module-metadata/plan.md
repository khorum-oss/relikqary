# Implementation Plan: Gradle Module Metadata & Gradle-First Browsing

**Branch**: `claude/spec-011-gradle-module-metadata` | **Date**: 2026-06-29 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/011-gradle-module-metadata/spec.md`

## Summary

Make Gradle a first-class client with full Gradle Module Metadata (GMM) fidelity, and surface Gradle
modules in the browse UI. The storage/protocol layers already preserve `.module` bytes faithfully, and
the existing path classification already makes a release `.module` immutable, a snapshot `.module`
overwritable, and proxy-caches a versioned `.module` (rather than passing it through like
`maven-metadata.xml`) — so the core publish/resolve/proxy behaviour is already correct and this feature
**proves it** with a real Gradle publish→consume round-trip that uses a feature variant/capability
(resolvable only via the `.module`), on hosted and proxy repos. On top of that it adds: explicit
recognition of a coordinate-matching `.module`; a tolerant backend parser exposing a module's
variants/attributes/capabilities/dependencies/files through the browse API; gating of that new endpoint
like the rest of the browse API; and a frontend that badges Gradle modules, offers Gradle (Kotlin +
Groovy) and Maven consume snippets, and renders a module detail view. The Maven contract and existing
publish/resolve/auth are unchanged; both storage backends behave identically. No new dependencies
(Jackson is already present).

## Technical Context

**Language/Version**: Kotlin 2.3.21 on JDK 21; SvelteKit (TypeScript) frontend.

**Primary Dependencies**: Spring Boot 4.1.0 (Web), Jackson (already present, for GMM parsing). **No new
dependency** → `gradle/verification-metadata.xml` untouched. Real Gradle/Maven clients drive the
round-trip tests via the existing external-process harness.

**Storage**: Unchanged. `.module` files are stored/served/cached byte-for-byte as opaque artifacts; the
parser only reads bytes to present a read-only view and never alters them.

**Testing**: JUnit 5 unit (GMM parser, `.module` recognition) + `@SpringBootTest(RANDOM_PORT)` +
`HttpClient` integration (module browse endpoint, contents coordinate/module ref, authz) + a **real
Gradle round-trip** (publish a feature-variant library → consume the capability, hosted and via proxy)
using the external-process Gradle harness; frontend e2e/component tests (badge, snippets, module detail).

**Target Platform**: Linux server (Spring Boot fat jar) + browser SPA. Both storage backends.

**Project Type**: Web service (`backend/`) + SvelteKit frontend (`frontend/`).

**Performance Goals**: GMM parsing happens only on the browse "view module" path, never on publish/resolve.
No change to the publish/resolve hot paths.

**Constraints**: detekt zero, Kover holds, SonarCloud not regressed; dependency verification stays
enabled and **untouched** (no new deps). Faithful storage preserved — the parser is read-only.

**Scale/Scope**: ~4 backend production files (`RepositoryPath`, a `gradle/` model + parser, browse
controller/DTOs, an authz tweak) + ~4 frontend files (api client, badge, consume-snippets, module-detail
components, browse page wiring) + unit/integration/round-trip/e2e tests + docs.

## Constitution Check

*GATE: re-checked after Phase 1 design — PASS.*

- **I. Repository Contract & Client Compatibility** — PASS. Additive only: a new read-only browse
  endpoint (`/api/repositories/{repo}/module/**`) and UI; the Maven wire layout, resolution, and publish
  acceptance are unchanged. Maven clients that ignore `.module` are unaffected (FR-006). No configuration
  contract removed.
- **II. Test-First & Integration-Verified** — PASS, strengthened. The centrepiece is a **real Gradle
  round-trip** that publishes a feature-variant/capability library and resolves it through a separate
  real Gradle build via the `.module` (variant selection that POM fallback cannot satisfy), on a hosted
  repo and through a proxy. Existing Maven/Gradle POM round-trips stay green. Both storage backends
  exercised for module browse.
- **III. Quality Gates** — PASS. detekt/Kover/Sonar unchanged; no gate weakened. No new dependency → no
  verification-metadata edit.
- **IV. Supply-Chain Integrity & Faithful Storage** — PASS, reinforced. `.module` files and their
  checksum/signature sidecars are stored/served byte-for-byte; the parser is strictly read-only and never
  re-checksums, rewrites, or validates contents. No server-side variant selection.

No violations → Complexity Tracking not required.

## Project Structure

### Documentation (this feature)

```text
specs/011-gradle-module-metadata/
├── plan.md, research.md, data-model.md, quickstart.md
├── contracts/module-api.md
├── checklists/requirements.md
└── tasks.md   # Phase 2 (/speckit-tasks — not created here)
```

### Source Code (repository root)

```text
backend/src/main/kotlin/org/khorum/oss/relikquary/
├── coordinate/
│   └── RepositoryPath.kt                 # MODIFIED: artifactId/version accessors + isModuleMetadata()
├── gradle/
│   ├── GradleModuleMetadata.kt           # NEW: parsed model (component, variant, attrs, caps, deps, files)
│   └── GradleModuleMetadataParser.kt     # NEW: tolerant Jackson parser (malformed ⇒ graceful)
├── protocol/
│   ├── BrowseController.kt               # MODIFIED: contents → coordinate+module ref; GET /module/**
│   └── dto/BrowseDtos.kt                 # MODIFIED: Coordinate + ModuleRef on ContentsResponse; module DTOs
└── security/
    └── RepositoryAuthorizationManager.kt # MODIFIED: `/module` browse sub-resource ⇒ READ (gates private)

backend/src/test/kotlin/org/khorum/oss/relikquary/
├── unit/
│   ├── GradleModuleMetadataParserTest.kt # NEW: variants/attrs/caps/deps/files; malformed ⇒ graceful
│   └── RepositoryPathModuleTest.kt        # NEW: recognition (release/snapshot match; non-matching reject)
└── integration/
    ├── ModuleBrowseApiTest.kt            # NEW: /module parsed response; contents coordinate/module; authz
    └── GradleModuleRoundTripTest.kt      # NEW: real Gradle feature-variant publish→consume (hosted+proxy)

frontend/src/lib/
├── api.ts                                # MODIFIED: module types + moduleMetadata() + ContentsResponse fields
├── components/
│   ├── GradleModuleBadge.svelte          # NEW: "Gradle module" indicator
│   ├── ConsumeSnippets.svelte            # NEW: Gradle Kotlin / Gradle Groovy / Maven tabs + copy
│   └── ModuleDetail.svelte               # NEW: variants → attributes/capabilities/dependencies/files
└── routes/r/[repo]/[...path]/+page.svelte # MODIFIED: wire badge + snippets + module detail
frontend/tests/                           # NEW/extended: e2e for badge, snippets, module detail
```

**Structure Decision**: A new `gradle/` package holds the GMM model + tolerant parser, kept separate from
storage/protocol so parsing is a pure, unit-testable read-only concern. Recognition lives on
`RepositoryPath` (the existing coordinate type) as `isModuleMetadata()`, leaving `classify()` — and hence
the already-correct publish/proxy decisions — untouched. The browse API gains a coordinate/module-aware
contents response and one new read endpoint; the new sub-resource is gated through the existing
authorization manager. The frontend adds three presentational components wired into the existing browse
page.

## Implementation phases (high level)

1. **Recognition** — `RepositoryPath` gains `artifactId`/`version` accessors and `isModuleMetadata()`
   (coordinate-matching `.module`); unit-test recognition and lock the existing release-immutable/
   snapshot-overwritable + proxy-cache behaviour with assertions.
2. **GMM model + parser** — `gradle/GradleModuleMetadata.kt` + a tolerant `GradleModuleMetadataParser`
   (malformed ⇒ a graceful "unparseable" result, never an exception to the caller); unit tests.
3. **Browse API** — `ContentsResponse` exposes the coordinate and a module ref when a version dir has a
   recognized `.module`; new `GET /api/repositories/{repo}/module/**` returns the parsed structured
   response; gate `/module` as READ in the authorization manager.
4. **Real Gradle round-trip** — publish a feature-variant/capability library from real Gradle to a hosted
   repo and resolve the capability from a separate real Gradle build (GMM-only); repeat through a proxy;
   assert byte-identical resolution. Confirm existing POM round-trips stay green.
5. **Frontend** — badge Gradle modules, render Gradle (Kotlin + Groovy) and Maven consume snippets, and a
   module detail view from the parsed response; wire into the browse page; e2e tests.
6. **Verify & document** — `./gradlew build` + frontend build/tests green (verification-metadata
   untouched); document Gradle support in `README.md`; commit & push to the feature branch (PR #15).

## Complexity Tracking

No constitution violations; section intentionally empty.
