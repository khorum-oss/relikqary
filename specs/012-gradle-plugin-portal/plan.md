# Implementation Plan: First-Class Gradle Plugin Portal Support

**Branch**: `claude/spec-012-gradle-plugin-portal` | **Date**: 2026-06-29 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/012-gradle-plugin-portal/spec.md`

## Summary

Make the Gradle Plugin Portal a first-class, out-of-the-box proxied upstream by **configuration**, reusing
the existing proxy/group machinery from feature 006 unchanged. Ship a default `gradle-plugins` proxy repo
whose upstream is the portal's Maven-layout endpoint (`https://plugins.gradle.org/m2/`, overridable via
`RELIKQUARY_GRADLE_PLUGIN_PORTAL_URL`), and add it as the **last** member of the existing default `public`
group (`[releases, maven-central, gradle-plugins]`). A Gradle build whose `pluginManagement` repositories
point at `…/public` then resolves plugin marker POMs and implementation artifacts through Relikquary, which
caches them byte-for-byte and serves cache hits offline — exactly as feature 006 already does for Maven
Central. No new repository kind, no new code path, no new dependency: the only production change is
`application.yml`. Verification adds a guarded real-Gradle round-trip (auto-skip offline) plus a config
assertion, and operator docs gain the plugin-portal default and the client `pluginManagement` snippet.

## Technical Context

**Language/Version**: Kotlin 2.3.21 on the JDK 21 toolchain.

**Primary Dependencies**: Spring Boot 4.1.0, Spring Security 7.1.0. **No new runtime dependency** — the
plugin portal reuses the existing `proxy` kind (`UpstreamClient` over `java.net.http.HttpClient`) and
`group` kind (`RepositoryResolver` first-match). No code classes are added or modified for resolution.

**Storage**: Existing `ArtifactStorage` (filesystem / S3). Plugin artifacts cache under the existing
`{proxyName}/{path}` key scheme (`gradle-plugins/…`); no storage interface change, identical on both backends.

**Testing**: JUnit 5 + `@SpringBootTest`; a guarded real-Gradle round-trip resolving a real published plugin
through `…/public` against the live portal (auto-skips when offline, like `ProxyCentralIT`); plus an
integration/config test asserting the default `gradle-plugins` proxy exists and `public` includes it last.

**Target Platform**: Linux server (Spring Boot fat jar).

**Project Type**: Web service (`backend/` module; `frontend/` unaffected).

**Performance Goals**: Plugin-portal placed last in `public` so ordinary dependency misses resolve from
Maven Central without an extra portal round-trip; cache hits served from local storage with no upstream I/O.

**Constraints**: Strict Gradle dependency verification stays enabled and **untouched** (no new deps);
detekt zero violations; Kover thresholds hold. Upstream URL via config/env; no secrets (the portal needs none).

**Scale/Scope**: One backend module. Production change is one default repo + one group-member edit in
`application.yml`. Plus one guarded round-trip test, one config assertion, and docs. Zero new/edited
production Kotlin classes expected.

## Constitution Check

*GATE: re-checked after Phase 1 design — PASS.*

- **I. Repository Contract & Client Compatibility** — PASS. Purely additive: a new default proxy and an
  extra member appended to the default group. Existing repositories (`releases`, `snapshots`,
  `maven-central`, `public`) keep their names and behavior; `public` only gains a trailing member, so any
  coordinate that resolved before still resolves the same way (first-match is order-preserving for existing
  members). No layout/resolution/publish-acceptance change. No MAJOR bump.
- **II. Test-First & Integration-Verified** — PASS. A real Gradle client resolves a real plugin through the
  proxy end-to-end (FR-011), gated to auto-skip offline (mirrors the justified guarded real-Central test
  from 006). The cache-hit/offline path is the same code already integration-tested in 006; we add a
  config-level assertion for the shipped default. No mocks substitute for the resolution path.
- **III. Quality Gates** — PASS. No gate weakened; detekt/Kover unchanged. No new dependency → no
  `verification-metadata.xml` edit.
- **IV. Supply-Chain Integrity & Faithful Storage** — PASS, reinforced. Plugin bytes (marker POM,
  implementation jar/POM, checksums, signatures) are cached through the existing faithful write path,
  byte-for-byte. Zero new dependencies keeps the trust surface unchanged. The portal endpoint is public and
  needs no credentials; the URL is config/env-only.

No violations → Complexity Tracking not required.

## Project Structure

### Documentation (this feature)

```text
specs/012-gradle-plugin-portal/
├── plan.md              # This file
├── research.md          # Phase 0 decisions (D1–D6)
├── data-model.md        # Config entities + resolution walkthrough (no schema change)
├── quickstart.md        # End-to-end validation scenarios
├── contracts/
│   └── plugin-portal.md # Default config + client contract
├── checklists/
│   └── requirements.md  # Spec quality checklist (from /speckit-specify + /speckit-clarify)
└── tasks.md             # Phase 2 output (/speckit-tasks)
```

### Source Code (repository root)

```text
backend/
├── src/main/resources/
│   └── application.yml                      # ADD gradle-plugins proxy; APPEND it to public group members
└── src/test/kotlin/org/khorum/oss/relikquary/
    ├── integration/
    │   ├── GradlePluginPortalRoundTripIT.kt # NEW — guarded real-Gradle plugin resolution through /public
    │   └── (existing proxy/group tests unchanged)
    └── repository/ or config/
        └── DefaultRepositoriesTest.kt        # NEW or extended — assert default gradle-plugins proxy + public order

README.md / specs/012-.../quickstart.md       # Document the portal default, env override, and pluginManagement snippet
```

**Structure Decision**: Single existing `backend/` module. This feature is configuration + verification +
docs; it deliberately introduces no new production Kotlin source, relying entirely on the feature-006
proxy/group implementation. The `frontend/` module is untouched.

## Complexity Tracking

No constitutional violations; section intentionally empty.
