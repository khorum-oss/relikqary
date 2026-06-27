# Implementation Plan: Web UI for Browsing & Managing Artifacts

**Feature**: `005-frontend-ui` (branch `claude/spec-005-frontend`) | **Date**: 2026-06-27 | **Spec**: [spec.md](./spec.md)

## Summary

Add a JSON browse/manage API to the backend (`/api/**`, separate from the Maven protocol) plus a
SvelteKit web UI to list repositories, browse contents, view file details, download, and delete.
Browsing/deleting require new `ArtifactStorage.list`/`delete` operations implemented for both
filesystem and S3. The UI is a separate module, runnable standalone, with opt-in bundling into the
backend. Naming throughout: package `org.khorum.oss.relikquary`, config `relikquary.*`.

## Technical Context

Backend: Kotlin 2.3.21 / JDK 21 / Spring Boot 4.1. Frontend: SvelteKit (Svelte 5) + Vite +
TypeScript, `adapter-static`; Node 22. Tests: backend JUnit + s3mock; frontend Playwright (Chromium
pre-installed). npm registry reachable.

## Constitution Check

- **I. Repository Contract** — additive `/api/**` JSON surface; Maven protocol unchanged. PASS.
- **II. Test-First & Integration-Verified** — storage list/delete tested on real boundaries (temp dir
  + s3mock); API integration-tested; UI exercised by a real-browser Playwright e2e against a running
  backend. PASS.
- **III. Quality Gates** — backend detekt/Kover unchanged; frontend adds eslint + a UI test gate.
- **IV. Faithful Storage** — browse/download never mutate bytes; delete is the only new mutation,
  gated by auth. PASS.

## Phase 0 — Decisions

- **Storage ops**: add `list(prefix): List<StorageEntry>` (name, isDirectory, size?, lastModified?)
  using hierarchical listing — filesystem `Files.list(dir)`; S3 `listObjectsV2` with `delimiter="/"`
  (commonPrefixes = folders, contents = files). Add `delete(key)` and `deletePrefix(prefix)` —
  filesystem walk-delete; S3 list+`deleteObjects`. Extend the `ArtifactStorage` interface (both impls).
- **Browse key**: `"{repo}/{path}"`, the same namespacing as 004; validate via `RepositoryPath`
  (path may be empty = repo root). Repo via `RepositoryRegistry` (404 unknown).
- **API** (`protocol/BrowseController.kt`, `@RequestMapping("/api")`):
  - `GET /api/repositories` → `[{name,type}]`
  - `GET /api/repositories/{repo}/contents/**` → `{path, entries:[{name,kind,size,lastModified}]}`
  - `GET /api/repositories/{repo}/file/**` → file details (+ checksums from sibling sidecars)
  - `DELETE /api/repositories/{repo}/**` → delete file or prefix
  - download reuses the existing Maven `GET /{repo}/**`.
- **Security**: `GET /api/**` permitAll; `DELETE /api/**` requires `hasRole(PUBLISH)`; both open when
  `security.enabled=false`. Extend `config/SecurityConfig.kt`.

## Phase 1 — Design

- DTOs (`protocol/dto/…`): `RepositorySummary`, `ListingEntry`, `FileDetails`.
- Frontend pages (`frontend/src/routes/`): `/` (repos), `/r/[repo]/[...path]` (browse + file detail),
  with a download link and a delete action (sends Basic auth header when required). A small `api.ts`
  client. Vite dev `server.proxy` routes `/api` + `/{repo}` to the backend.
- Gradle: a `frontend` module with Exec tasks (`npmInstall`, `npmBuild` → `frontend/build`), no new
  Gradle plugins (avoids verification-metadata churn). Backend `processResources` copies
  `frontend/build` into `static/` only when `-PbundleFrontend` is set (opt-in bundling, FR-008).

## Critical files

Backend new: `protocol/BrowseController.kt`, `protocol/dto/*.kt`, `storage/StorageEntry.kt`.
Backend modified: `storage/ArtifactStorage.kt` (+`list`/`delete`/`deletePrefix`),
`storage/FilesystemArtifactStorage.kt`, `storage/S3ArtifactStorage.kt`, `config/SecurityConfig.kt`.
Frontend new: `frontend/` SvelteKit project. Build: `frontend/build.gradle.kts`, `settings.gradle.kts`
(`include("frontend")`), backend `build.gradle.kts` (opt-in bundle copy), `README.md`.

## Reuse

- `RepositoryRegistry` / `RepositoryPath` (validation) / `RepositoryType`; the existing Maven `GET`
  for downloads; `SecurityConfig` pattern; s3mock external-process harness for S3 list/delete tests.

## Verification

- `./gradlew build` green (backend), incl. storage list/delete tests (temp dir + s3mock) and
  `BrowseApiTest` (HTTP). Frontend: `npm run build` + `npm run test:e2e` (Playwright) drives a real
  browser against a running backend seeded with an artifact: list → browse → details → download →
  delete. Manual: `npm run dev` (proxy to backend) and `-PbundleFrontend` served by the backend.
