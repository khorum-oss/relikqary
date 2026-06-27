---
description: "Task list for the browse/manage Web UI"
---

# Tasks: Web UI for Browsing & Managing Artifacts

**Input**: `specs/005-frontend-ui/`. **Tests**: included (Principle II).

## Phase 1: Backend storage operations

- [ ] T001 Add `storage/StorageEntry.kt` (name, isDirectory, size?, lastModified?) and extend `storage/ArtifactStorage.kt` with `list(prefix): List<StorageEntry>`, `delete(key): Boolean`, `deletePrefix(prefix): Int`
- [ ] T002 Implement the new ops in `storage/FilesystemArtifactStorage.kt` (Files.list for one level; walk-delete) with traversal-safe resolution
- [ ] T003 Implement the new ops in `storage/S3ArtifactStorage.kt` (listObjectsV2 + delimiter "/"; deleteObject/deleteObjects)
- [ ] T004 [P] Unit test filesystem list/delete (`@TempDir`); s3mock integration for S3 list/delete (extend `S3RoundTripTest` harness)

## Phase 2: Backend browse/manage API

- [ ] T005 Add DTOs `protocol/dto/{RepositorySummary,ListingEntry,FileDetails}.kt`
- [ ] T006 Add `protocol/BrowseController.kt` (`/api`): list repositories; list contents; file details (read sibling checksums); DELETE (file or prefix) — repo via RepositoryRegistry (404), path via RepositoryPath
- [ ] T007 Extend `config/SecurityConfig.kt`: `GET /api/**` permitAll; `DELETE /api/**` requires PUBLISH; open when disabled
- [ ] T008 [P] Integration test `integration/BrowseApiTest.kt`: repositories list; browse a seeded artifact; file details + checksums; DELETE auth matrix (401 no-cred, 200 with publisher), removal reflected + Maven GET 404 after

## Phase 3: SvelteKit frontend module

- [ ] T009 Scaffold `frontend/` SvelteKit (Svelte 5 + TS + adapter-static); `api.ts` client; Vite dev proxy for `/api` and repo download paths
- [ ] T010 Pages: `/` repositories list; `/r/[repo]/[...path]` browse + file-details panel with download; delete action (Basic auth header when required)
- [ ] T011 `settings.gradle.kts` include `frontend`; `frontend/build.gradle.kts` Exec tasks (npmInstall/npmBuild → frontend/build); backend `build.gradle.kts` opt-in `-PbundleFrontend` copy into static resources

## Phase 4: Tests, docs, verify

- [ ] T012 Frontend Playwright e2e (`frontend/tests/`): start backend (seeded artifact) + preview build; list → browse → details → download → delete
- [ ] T013 [P] README/quickstart: run UI standalone (`npm run dev`), bundled (`-PbundleFrontend`), and the API surface
- [ ] T014 `./gradlew build` green (backend) + `npm run build` + e2e; commit & push

## Dependencies

Storage ops (P1) → API (P2) → frontend (P3) → tests/docs (P4). T006 depends on T001–T003 + RepositoryRegistry/RepositoryPath.
