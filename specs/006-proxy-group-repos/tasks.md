---
description: "Task list for proxy (remote) repositories & repository groups"
---

# Tasks: Proxy (Remote) Repositories & Repository Groups

**Input**: `specs/006-proxy-group-repos/`. **Tests**: included (Constitution Principle II — real
round-trips). All paths under `backend/src/.../org/khorum/oss/relikquary/`.

## Phase 1: Setup

- [X] T001 No new dependencies or scaffolding needed (JDK `HttpClient`/`HttpServer` only) — confirm
  `gradle/verification-metadata.xml` stays untouched and create the `proxy/` package directory under
  `backend/src/main/kotlin/org/khorum/oss/relikquary/proxy/`.

## Phase 2: Foundational (blocking prerequisites for all stories)

- [X] T002 Add `repository/RepositoryKind.kt` enum `{HOSTED, PROXY, GROUP}`.
- [X] T003 Extend `config/RepositoryProperties.kt` `Repo` with `kind: RepositoryKind = HOSTED`,
  `remoteUrl: String? = null`, `remoteUsername: String? = null`, `remotePassword: String? = null`,
  `members: List<String> = emptyList()` (existing `name`/`type` unchanged).
- [X] T004 Add startup validation to `repository/RepositoryRegistry.kt`: PROXY requires non-blank
  `remoteUrl`; GROUP requires ≥1 member, all members configured, no self-reference, no nested groups —
  throw on invalid config (context fails to start).
- [X] T005 [P] Unit test `unit/RepositoryRegistryTest.kt`: extend with proxy/group validation matrix
  (valid proxy/group; proxy missing url; group empty/unknown-member/self-ref/nested-group → throws).
- [X] T006 Add `repository/RepositoryResolver.kt` with sealed `Resolution { Hit(StoredArtifact), Miss,
  UpstreamError }` and `resolve(repoName, path): Resolution`; implement the **HOSTED** branch
  (`storage.openRead("{name}/{path}")`) and wire dispatch on `kind` (PROXY/GROUP branches stubbed to
  `Miss` for now).
- [X] T007 Refactor `protocol/RepositoryController.kt`: GET delegates to `RepositoryResolver` and maps
  `Hit`→200 / `Miss`→404 / `UpstreamError`→502; `PUT` to a non-HOSTED repo returns `405` with
  `Allow: GET, HEAD`. Existing hosted publish/resolve behavior unchanged.
- [X] T008 [P] Confirm the existing hosted suites still pass after the refactor
  (`RepositoryRoutingTest`, `RepositoryHttpTest`, `PublishResolveRoundTripTest`, `BrowseApiTest`) —
  `./gradlew :backend:test`.

**Checkpoint**: hosted repos resolve via the new resolver with zero behavior change; non-hosted kinds
route but resolve empty.

## Phase 3: User Story 1 — Resolve through a proxy (Priority: P1)

**Goal**: A proxy fetches from its upstream on a cache miss, caches immutable bytes, and serves hits
without upstream contact; metadata is pass-through; missing→404, upstream error→502; publish→405.

**Independent test**: Resolve an artifact through a proxy on a cold cache (fetched from a stub upstream,
byte-for-byte), then again with the upstream stopped (served from cache).

- [X] T009 [US1] Add `proxy/UpstreamClient.kt` over JDK `HttpClient` (with `ProxySelector.getDefault()`,
  `followRedirects(Redirect.NORMAL)` so upstream 301/302 — common on Maven Central — resolve rather than
  fail, optional Basic auth from `remoteUsername`/`remotePassword`); `fetch(repo, artifactPath): UpstreamResponse`
  sealed `{ Found(stream, contentLength?), NotFound, Error }` (404/410→NotFound; 200→Found; connect/
  timeout/5xx→Error).
- [X] T010 [US1] Implement the **PROXY** branch in `repository/RepositoryResolver.kt`: `maven-metadata.xml`
  → pass-through `UpstreamClient.fetch` (never cached); other paths cache-first — cached⇒`Hit`; else fetch,
  on `Found` `storage.write(cacheKey, body)` then `openRead`⇒`Hit`, `NotFound`⇒`Miss`, `Error`⇒`UpstreamError`.
- [X] T011 [P] [US1] Unit test `unit/RepositoryResolverTest.kt`: PROXY dispatch with mocked storage +
  upstream — cache hit (no upstream call), miss→fetch+write→hit, metadata pass-through (no write), NotFound→Miss,
  Error→UpstreamError.
- [X] T012 [US1] Add a reusable local stub upstream test helper (JDK `com.sun.net.httpserver.HttpServer`)
  under `backend/src/test/.../integration/` serving a canned jar/pom/`.sha1`/`maven-metadata.xml`.
- [X] T013 [US1] Integration test `integration/ProxyResolveTest.kt` (`@SpringBootTest` + stub upstream via
  `@DynamicPropertySource`): cache miss→hit (second request served with stub stopped), metadata pass-through,
  unknown coordinate→404, upstream-down on cold miss→502, `PUT`→405.
- [X] T014 [US1] Round-trip test `integration/ProxyRoundTripTest.kt`: a real Maven/Gradle client resolves a
  dependency **through the proxy** (stub upstream serving a real artifact), bytes match upstream, cache
  populated, and a second resolve succeeds with the upstream stopped.
- [X] T015 [P] [US1] Guarded test `integration/ProxyCentralIT.kt`: proxy pointed at real Maven Central,
  auto-skipped offline (reachability probe → `Assumptions.assumeTrue`); resolve a small known artifact.
- [X] T016 [US1] Ship the `maven-central` proxy default in `backend/src/main/resources/application.yml`
  (`kind: proxy`, `remoteUrl: https://repo1.maven.org/maven2`).

**Checkpoint**: proxy resolve/caching fully works and is independently demonstrable.

## Phase 4: User Story 2 — Resolve through a group URL (Priority: P1)

**Goal**: A group aggregates ordered hosted/proxy members and returns the first match; all-miss→404; a
proxy member upstream error with no hit→502; publish→405.

**Independent test**: Configure a group over a hosted member (+ a proxy member); resolve a first-party
artifact (hosted) and a third-party dependency (proxy) through the one group URL.

- [X] T017 [US2] Implement the **GROUP** branch in `repository/RepositoryResolver.kt`: iterate `members`
  in order, recursively resolve each — first `Hit` returns; `Miss` continues; remember `UpstreamError`;
  after all members, remembered error⇒`UpstreamError` else `Miss`.
- [X] T018 [P] [US2] Unit test `unit/RepositoryResolverTest.kt`: GROUP ordering (first member wins),
  fall-through to a later member, all-miss→Miss, member upstream error with no hit→UpstreamError.
- [X] T019 [US2] Integration test `integration/GroupResolveTest.kt`: first-match returns the hosted
  member's bytes; a path only a proxy member can satisfy resolves (and caches) via that member; absent
  everywhere→404; `PUT`→405.
- [X] T020 [US2] Ship the `public` group default in `backend/src/main/resources/application.yml`
  (`kind: group`, `members: [releases, maven-central]`); extend the group round-trip coverage in
  `ProxyRoundTripTest`/`GroupResolveTest` to resolve both a first-party and a proxied dependency through
  the single group URL.

**Checkpoint**: one group URL serves first-party + proxied dependencies.

## Phase 5: User Story 3 — Read-only kinds; existing behavior preserved (Priority: P2)

**Goal**: Proxy/group reject publish (405); browse surfaces the new kinds; all existing capabilities
unchanged.

**Independent test**: `PUT` to a proxy/group is rejected; the existing hosted round-trip and DELETE auth
matrix still pass.

- [X] T021 [US3] Surface `kind` in the browse API: include it in the repository summary returned by
  `protocol/BrowseController.kt` `GET /api/repositories` (additive); a group's `contents` listing returns
  empty (no backing storage prefix).
- [X] T022 [P] [US3] Integration test in `integration/BrowseApiTest.kt`: `GET /api/repositories` reports
  `kind` for hosted/proxy/group; a proxy's cached file is browsable; a group's contents listing is empty.
- [X] T023 [P] [US3] Confirm the read-only `405` assertions exist for both proxy and group (in
  `ProxyResolveTest`/`GroupResolveTest`) and that `AuthPublishTest`/`AuthDisabledTest`/`BrowseDeleteAuthTest`
  still pass unchanged.

## Phase 6: Polish & cross-cutting

- [X] T024 [P] Update `README.md` and `specs/006-proxy-group-repos/quickstart.md` references: configuring
  proxy/group repos, the shipped `maven-central`/`public` defaults, upstream credentials via env/file, and
  the read-only (405) + cache/metadata semantics.
- [X] T025 `./gradlew build` green (detekt zero + Kover + all unit/integration incl. proxy/group
  round-trips; real-Central IT skips offline; `verification-metadata.xml` untouched); commit & push to
  `claude/spec-006-proxy-repos`.

## Dependencies

Foundational (Phase 2, T002–T008) blocks everything. US1 (T009–T016) and US2 (T017–T020) both build on the
resolver; US2's hosted-only first-match is independent, while the combined hosted+proxy group scenario
(T020) depends on US1's proxy branch. US3 (T021–T023) depends on the controller `405` wiring (T007) and
benefits from US1/US2 repos existing. Polish (T024–T025) last.

## Parallel opportunities

- T005 (registry test) ∥ T002–T004 authoring once interfaces exist.
- Within US1: T011 (unit) ∥ T013/T014 (integration) ∥ T015 (guarded IT) — different files.
- T018 (US2 unit) ∥ T019 integration. T022/T023 (US3 tests) ∥ each other.

## MVP scope

Foundational + **User Story 1** (proxy resolve/caching) is the MVP — it delivers the core
repository-manager capability (one cacheable entry point for external dependencies). User Story 2 (group
aggregation) is the natural immediate follow-on.
