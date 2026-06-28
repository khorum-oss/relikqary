---
description: "Task list for per-repository authorization"
---

# Tasks: Per-Repository Authorization

**Input**: `specs/007-per-repo-authz/`. **Tests**: included (Constitution Principle II —
security-critical behaviour). All paths under `backend/src/.../org/khorum/oss/relikquary/`.

## Phase 1: Setup

- [X] T001 No new dependencies (Spring Security already present) — confirm `gradle/verification-metadata.xml`
  stays untouched and create the `security/` package directory under
  `backend/src/main/kotlin/org/khorum/oss/relikquary/security/`.

## Phase 2: Foundational (blocking prerequisites for all stories)

- [X] T002 Add `RepositoryAccess` (`read`/`publish`/`delete`: `List<String>?`) to
  `config/RepositoryProperties.kt` and an optional `access: RepositoryAccess? = null` field on `Repo`
  (additive; existing config unchanged).
- [X] T003 Add `security/Action.kt` enum `{ READ, PUBLISH, DELETE }`.
- [X] T004 Add `security/RepositoryAuthorizer.kt` (`@Component`): `permits(repo, action, authentication):
  Boolean` — short-circuit `true` when `relikquary.security.enabled=false`; READ with no list ⇒ open;
  PUBLISH/DELETE with no list ⇒ require authority `ROLE_PUBLISH`; with a list ⇒ match `authentication.name`
  or authority `ROLE_<r>` for each `@r`. Inject `SecurityProperties` for the enabled flag.
- [X] T005 [P] Unit test `unit/RepositoryAuthorizerTest.kt`: defaults (read open; publish/delete need
  `PUBLISH`), explicit grant overrides default, username vs `@role` matching, anonymous denied, disabled
  ⇒ all permitted.
- [X] T006 Add `security/RepositoryAuthorizationManager.kt` implementing
  `AuthorizationManager<RequestAuthorizationContext>`: **unwrap the `Supplier<Authentication>`** (call
  `.get()`) before deciding; parse request → `(repoName, action)` per data-model mapping; unknown repo /
  GROUP / `PUT` to PROXY|GROUP ⇒ grant (controller returns 404/405 or resolver handles group); `GET
  /api/repositories`, `/api`, `/ui/**` ⇒ grant; otherwise return
  `AuthorizationDecision(authorizer.permits(repo, action, auth))`.
- [X] T007 [P] Unit test `unit/RepositoryAuthzRequestMappingTest.kt`: Maven `GET`/`PUT` `/{repo}/**`,
  browse `GET .../contents|file/**`, `DELETE /api/repositories/{repo}/**`, and the always-granted paths
  map to the right `(repo, action)` (or pass-through).
- [X] T008 Wire the manager in `config/SecurityConfig.kt`: when enabled, replace the static `PUT`/`DELETE`
  `hasRole(PUBLISH)` rules with `authorizeHttpRequests { it.anyRequest().access(manager) }`, keeping
  `httpBasic` (realm + Basic entry point) and the `permitAll` branch when disabled.
- [X] T009 [P] Confirm the existing suites still pass unchanged (defaults preserve behaviour):
  `AuthPublishTest`, `AuthDisabledTest`, `RepositoryRoutingTest`, `BrowseApiTest`, `BrowseDeleteAuthTest`,
  `ProxyResolveTest`, `GroupResolveTest` — `./gradlew :backend:test`.

**Checkpoint**: with no `access` blocks, behaviour is identical to today; per-repo grants are enforced for
hosted/proxy READ/PUBLISH/DELETE via the manager with correct `401`/`403`.

## Phase 3: User Story 1 — Per-repository publish control (Priority: P1)

**Goal**: Only permitted principals may publish to a restricted repo; others `403`; unauth `401`; repos
without a publish grant keep the global-`PUBLISH` default.

**Independent test**: Grant publish on repo X to alice; alice publishes (201), bob `403`, anonymous `401`;
a default repo still accepts a global `PUBLISH` holder.

- [X] T010 [US1] Integration test `integration/PerRepoPublishAuthzTest.kt`: repo with `publish:[alice]` ⇒
  alice `PUT` 201, bob `PUT` 403, anonymous `PUT` 401; a repo with no grant ⇒ global `PUBLISH` holder 201
  and an authenticated non-publisher 403; an authenticated `PUT` to a proxy/group ⇒ `405` (read-only kind
  precedes authz, FR-008).

**Checkpoint**: publishing is scoped per repository.

## Phase 4: User Story 2 — Private repositories (restrict reads) (Priority: P1)

**Goal**: A read-restricted repo is readable only by permitted principals over both the Maven path and the
browse API; unrestricted repos stay open.

**Independent test**: Mark repo X `read:[alice]`; alice reads (200), bob `403`, anonymous `401`; an open
repo reads anonymously; the browse API enforces the same rule.

- [X] T011 [US2] Integration test `integration/PrivateRepoReadTest.kt` (Maven path): `read:[alice]` repo ⇒
  alice `GET` 200, bob `GET` 403, anonymous `GET` 401; a repo with no read grant ⇒ anonymous `GET` 200;
  read applies to `maven-metadata.xml`/checksum siblings too.
- [X] T012 [P] [US2] Extend `PrivateRepoReadTest.kt` for browse-API parity: `GET
  /api/repositories/{repo}/contents` obeys the read policy (bob 403, alice 200); `GET /api/repositories`
  (list names) returns 200 for anyone.

**Checkpoint**: private repositories are enforced consistently on both read surfaces.

## Phase 5: User Story 3 — Consistent delete, groups, and the disable switch (Priority: P2)

**Goal**: Per-repo permissions apply to DELETE and to group reads (permissive union), and disabling
security opens everything.

**Independent test**: delete obeys the delete policy; a group read is governed by the serving member's
read policy (skip-and-continue); security disabled ⇒ all open.

- [X] T013 [US3] Implement permissive-union read in `repository/RepositoryResolver.kt` `group(...)`: read
  the current `Authentication` from `SecurityContextHolder`; before delegating to a member, skip it when
  `authorizer.permits(member, READ, auth)` is false; serve the first member that both has the artifact and
  permits the user, else `Miss` (404). Inject `RepositoryAuthorizer`.
- [X] T014 [US3] Integration test `integration/PerRepoDeleteAuthzTest.kt`: repo with `delete:[alice]` ⇒
  alice `DELETE` 204, bob 403, anonymous 401; a repo with no delete grant ⇒ global `PUBLISH` holder 204.
- [X] T015 [P] [US3] Integration test `integration/GroupAuthzTest.kt`: a group over a private member
  (`read:[alice]`) and an open member; alice reads a private-only artifact through the group (200); bob is
  denied the private member and gets the open member's copy when present, else `404`; group reads never
  return `401`.
- [X] T016 [P] [US3] Integration test `integration/BackwardCompatAuthzTest.kt`: with no `access` blocks,
  open reads + global-`PUBLISH`-gated writes (regression); a traversal path still returns `400` (FR-011,
  unchanged); with `security.enabled=false`, every action on every repo is permitted.

**Checkpoint**: authorization is uniform across delete, browse, group, and the disable switch.

## Phase 6: Polish & cross-cutting

- [X] T017 [P] Document the `access` block in `backend/src/main/resources/application.yml` (commented
  example) and add a per-repository authorization section to `README.md` (grants, defaults/override,
  `401` vs `403`, group union, disable switch).
- [X] T018 `./gradlew build` green (detekt zero + Kover + all unit/integration incl. the authz matrices;
  `verification-metadata.xml` untouched); commit & push to `claude/spec-007-per-repo-authz`.

## Dependencies

Foundational (Phase 2, T002–T009) blocks everything — the policy function, manager, and filter wiring
are shared by all stories. US1 (T010) and US2 (T011–T012) are then test-only slices over the foundational
enforcement. US3 adds the resolver group change (T013) before its group test (T015); T014/T016 depend
only on foundational. Polish (T017–T018) last.

## Parallel opportunities

- T005 (authorizer unit) ∥ T007 (parser unit) — different files.
- Across stories once foundational is done: T010, T011/T012, T014, T015, T016 touch different test files
  and can be written in parallel; T013 (resolver) must precede T015.

## MVP scope

Foundational + **User Story 1** (per-repo publish control) is the MVP — it delivers scoped publishing,
the headline value. **User Story 2** (private repositories) is the immediate, equally-P1 follow-on;
together they cover the core read/write access model.
