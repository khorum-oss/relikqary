---
description: "Task list for multiple named repositories"
---

# Tasks: Multiple Named Repositories

**Input**: `specs/004-named-repositories/`. **Tests**: included (Principle II). Paths under `backend`.

## Phase 1: Foundational

- [ ] T001 [P] Add `config/RepositoryProperties.kt` (`@ConfigurationProperties("relikqary")`, `repositories: List<Repo>` of `{name, type}`) and `repository/RepositoryType.kt` enum (RELEASE, SNAPSHOT, MIXED)
- [ ] T002 Add `repository/RepositoryRegistry.kt` (lookup by name from properties; repo-name validation: single safe segment) — depends on T001
- [ ] T003 Register `RepositoryProperties` in `RelikqaryApplication.kt`; add default `releases`/`snapshots` repos to `application.yml`

## Phase 2: US2 — Repo-aware policy

- [ ] T004 [US2] Extend `ingestion/RepublishPolicy.kt`: `evaluate(type, path, exists): PublishDecision` (ACCEPT / REJECT_IMMUTABLE / REJECT_TYPE) using `RepositoryPath.classify()` and repo type; release/mixed honour the global overwrite flag — depends on T001
- [ ] T005 [P] [US2] Unit test `unit/RepublishPolicyTest.kt` (extend): matrix of release/snapshot/mixed × release/snapshot/metadata coordinate × exists → expected decision

## Phase 3: US1 — Routing

- [ ] T006 [US1] Update `protocol/RepositoryController.kt`: parse first segment as repo name (404 if unknown via registry), build artifact `RepositoryPath` from remainder, storage key = `"{repo}/{artifactKey}"`, map `PublishDecision` → 201/200/409/400 — depends on T002, T004
- [ ] T007 [P] [US1] Unit test `unit/RepositoryRegistryTest.kt` (lookup hit/miss, name validation)
- [ ] T008 [US1] Integration test `integration/RepositoryRoutingTest.kt`: PUT release→/releases 201, re-PUT 409; SNAPSHOT→/releases 400; release→/snapshots 400; snapshot→/snapshots 201, re-PUT 200; GET unknown repo 404; GET published 200; same coordinate in two repos isolated

## Phase 4: Update existing tests + e2e

- [ ] T009 Update existing HTTP tests to repo-prefixed paths: `integration/RepositoryHttpTest.kt`, `integration/AuthPublishTest.kt`, `integration/AuthDisabledTest.kt` (publish under `/releases` or `/snapshots`)
- [ ] T010 [US1] Update `integration/PublishResolveRoundTripTest.kt`: publish to `…/releases`, resolve via Maven + Gradle from `…/releases`; storage assertions under `releases/…`

## Phase 5: Polish

- [ ] T011 [P] Document repositories in `application.yml` and `README.md` (named repos, types, URL scheme)
- [ ] T012 `./gradlew build` green (compile + detekt + tests + Kover + strict verification); commit & push

## Dependencies

Foundational → policy → routing → update tests → polish. T006 depends on T002+T004.
