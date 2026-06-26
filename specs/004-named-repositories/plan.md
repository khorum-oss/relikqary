# Implementation Plan: Multiple Named Repositories

**Feature**: `004-named-repositories` (tracked via `.specify/feature.json`; branch `claude/spec-004-repos`) | **Date**: 2026-06-26 | **Spec**: [spec.md](./spec.md)

## Summary

Replace the single root repository with config-defined named, typed repositories addressed by a path
prefix (`/{repo}/{group}/…`). The repo type (`release`/`snapshot`/`mixed`) governs accepted coordinate
kinds and mutability. The repo name namespaces the storage key. No new dependencies; storage, auth,
and the artifact wire layout are unchanged.

## Technical Context

Kotlin 2.3.21 / JDK 21 / Spring Boot 4.1. No new deps. Single `backend` module.

## Constitution Check

- **I. Repository Contract** — Intentional, documented breaking change to the URL scheme (repo prefix
  now required); within a repo the Maven layout is unchanged, so standard clients work against
  `/{repo}`. PASS (versioned as a deliberate evolution).
- **II. Test-First & Integration-Verified** — PASS. Routing/policy matrix unit-tested; real Gradle/
  Maven round-trip against `/releases`.
- **III. Quality Gates** / **IV. Faithful Storage** — PASS (unchanged; bytes preserved, key just gains
  a repo prefix).

## Phase 0 — Decisions

- **Routing**: first path segment = repo name; look up in `RepositoryRegistry`; unknown ⇒ 404. Build
  the artifact path from the remainder via existing `RepositoryPath.of(...)`. Storage key =
  `"{repo}/{artifactKey}"`.
- **Policy** → repo-aware decision: reuse `RepositoryPath.classify()` (RELEASE/SNAPSHOT/METADATA) and
  the repo type to return `ACCEPT` / `REJECT_IMMUTABLE` (409) / `REJECT_TYPE` (400). The global
  `relikqary.publish.release-policy=overwrite` still relaxes release immutability for release/mixed.
- **Repo name validation**: a single safe segment (alphanumeric, `-`, `_`); no traversal.

## Phase 1 — Design

- `RepositoryProperties` (`@ConfigurationProperties("relikqary")`, `repositories: List<Repo>` of
  `{name, type}`); `RepositoryRegistry` (lookup) + `RepositoryType` enum (RELEASE, SNAPSHOT, MIXED).
- Contract delta in `contracts/named-repositories.md`.

## Critical files

New: `config/RepositoryProperties.kt`, `repository/RepositoryRegistry.kt` (+ `RepositoryType`).
Modified: `protocol/RepositoryController.kt` (repo parse, key namespacing, decision→status),
`ingestion/RepublishPolicy.kt` (repo-aware `evaluate`), `RelikqaryApplication.kt`
(`@EnableConfigurationProperties`), `application.yml` (default `releases`/`snapshots`),
`README.md`. Existing HTTP tests move under a repo prefix.

## Reuse

- `coordinate/RepositoryPath.kt` (validation + `classify()`), config pattern from
  `config/StorageProperties.kt`, the round-trip harness in `integration/PublishResolveRoundTripTest.kt`
  (change repo URLs to `…/releases`). `ArtifactStorage` untouched.

## Verification

`./gradlew build` green (detekt + Kover + strict verification). Unit: policy matrix + registry.
Integration: `RepositoryRoutingTest` (201/409/400/404 cases) + updated real round-trip publishing/
resolving at `/releases`.
