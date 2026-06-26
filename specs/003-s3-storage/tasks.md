---
description: "Task list for S3 / DigitalOcean Spaces storage backend"
---

# Tasks: S3 / DigitalOcean Spaces Storage Backend

**Input**: `specs/003-s3-storage/`. **Tests**: included (Principle II). Paths under the `backend` module.

## Phase 1: Setup

- [x] T001 Add `software.amazon.awssdk:s3` (impl) and the `com.adobe.testing:s3mock` `exec` jar + `org.testcontainers:minio` (test) to `gradle/libs.versions.toml` and `backend/build.gradle.kts`; add a resolvable `s3mock` configuration and pass its jar path to the `test` task as a system property

## Phase 2: Foundational

- [x] T002 [P] Extend `config/StorageProperties.kt`: `backend: Backend = FILESYSTEM` enum + `s3: S3` block (endpoint, region, bucket, accessKey, secretKey, pathStyleAccess=true)
- [x] T003 Add `config/S3ClientConfig.kt`: `@Bean S3Client` from the s3 config (endpoint override, region, static credentials, `forcePathStyle`), `@ConditionalOnProperty(backend=s3)`
- [x] T004 Annotate `storage/FilesystemArtifactStorage.kt` with `@ConditionalOnProperty("relikqary.storage.backend", havingValue="filesystem", matchIfMissing=true)`

## Phase 3: US1 — Persist to S3 (P1)

- [x] T005 [US1] Add `storage/S3ArtifactStorage.kt`: `exists` (headObject ⇒ false on NoSuchKey), `openRead` (getObject ⇒ StoredArtifact(stream, contentLength)), `write` (buffer to temp file, putObject(file), return size); `@ConditionalOnProperty(backend=s3)` — depends on T002, T003
- [x] T006 [P] [US1] Unit test `unit/S3ArtifactStorageTest.kt` (MockK `S3Client`): verifies head/get/put calls and not-found mapping
- [x] T007 [US1] Integration test `integration/S3RoundTripTest.kt`: start adobe/s3mock (`exec` jar) as an external process, point a real `S3Client` at it, and assert `S3ArtifactStorage` put/get/exists round-trips byte-for-byte (real S3 boundary; runs here)

## Phase 4: US2 — Backend selection + end-to-end (P1)

- [x] T008 [US2] Integration test confirming the filesystem backend is active by default and `backend=s3` activates S3 (bean wiring) — reuse `@SpringBootTest` + `@DynamicPropertySource`
- [x] T009 [US2] End-to-end: `@SpringBootTest` with `backend=s3` pointed at s3mock, run the real Gradle publish + Maven/Gradle resolve harness (extend `PublishResolveRoundTripTest` patterns) — optional if T007+existing e2e already cover the path
- [x] T010 [P] [US1] Docker-guarded Testcontainers MinIO test `integration/S3MinioIT.kt` (skips when Docker is unavailable) — honours the constitution's Testcontainers requirement for CI

## Phase 5: Polish

- [x] T011 Regenerate `gradle/verification-metadata.xml` (SHA-256) for AWS SDK + test deps
- [x] T012 [P] Document `relikqary.storage.backend`/`s3.*` in `application.yml` and `README.md`
- [x] T013 `./gradlew build` green (compile + detekt + tests + Kover + strict verification); commit & push

## Dependencies

Setup → Foundational → US1 → US2 → Polish. T005 depends on T002/T003. T011 after the dependency set is stable.
