# Implementation Plan: S3 / DigitalOcean Spaces Storage Backend

**Feature**: `003-s3-storage` (tracked via `.specify/feature.json`; branch `claude/spec-003-s3-storage`) | **Date**: 2026-06-26 | **Spec**: [spec.md](./spec.md)

## Summary

Add an S3-compatible `ArtifactStorage` implementation selectable via `relikquary.storage.backend`
(`filesystem` default, `s3` opt-in), using AWS SDK for Java v2. Protocol, auth (002), and wire layout
are unchanged — only where bytes live. Verified here against adobe/s3mock as an external process; a
Docker-guarded Testcontainers MinIO test is included for CI.

## Technical Context

Kotlin 2.3.21 / JDK 21 / Spring Boot 4.1 (unchanged). New dep: `software.amazon.awssdk:s3` (2.46.x).
Test: `com.adobe.testing:s3mock` (5.1.0, `exec` classifier — runnable jar, external process);
`org.testcontainers:minio` (1.21.4) for the Docker-guarded test. Single `backend` module.

## Constitution Check

- **I. Repository Contract** — PASS. No wire/layout/auth change (FR-008).
- **II. Test-First & Integration-Verified** — PASS *with documented deviation*: real S3 boundary via
  s3mock external process here; Testcontainers MinIO test present but Docker-guarded (skips here, runs
  in CI). The deviation (no Docker) is explicit, not silent.
- **III. Quality Gates** — PASS (detekt/Kover unchanged).
- **IV. Supply-Chain & Faithful Storage** — PASS. Bytes preserved (FR-004); credentials via env/config,
  never committed (FR-007); new deps added to `verification-metadata.xml`.

## Phase 0 — Decisions

- **SDK**: AWS SDK v2 `S3Client` (sync) — endpoint override + `forcePathStyle` for Spaces/MinIO/s3mock.
- **write length**: AWS `putObject` needs content length; the `ArtifactStorage.write(key, InputStream)`
  has none → buffer the stream to a temp file (reuse `FilesystemArtifactStorage`'s temp-file pattern),
  `putObject(RequestBody.fromFile)`, return size.
- **exists/read**: `headObject` (NoSuchKey ⇒ false); `getObject` ⇒ `StoredArtifact(stream, contentLength)`.
- **Bean selection**: `@ConditionalOnProperty("relikquary.storage.backend", havingValue=…)` on each
  impl (filesystem `matchIfMissing=true`); `S3Client` `@Bean` only when `backend=s3`.

## Phase 1 — Design

- **StorageProperties**: add `backend: Backend = FILESYSTEM` and `s3: S3` (endpoint, region, bucket,
  accessKey, secretKey, pathStyleAccess=true).
- **Contract delta** (`contracts/s3-backend.md`): object key = Maven-layout path; absent ⇒ 404;
  faithful bytes; no auth/wire change.

## Critical files

New: `storage/S3ArtifactStorage.kt`, `config/S3ClientConfig.kt`. Modified: `config/StorageProperties.kt`,
`storage/FilesystemArtifactStorage.kt` (`@ConditionalOnProperty`), `gradle/libs.versions.toml`,
`backend/build.gradle.kts` (+ a `s3mock` resolvable configuration whose jar path is passed to tests),
`gradle/verification-metadata.xml`, `application.yml`, `README.md`.

## Verification

`./gradlew build` green: filesystem tests unchanged + `S3ArtifactStorageTest` (mocked client, logic) +
`S3RoundTripTest` (s3mock external process, real boundary) + Docker-guarded MinIO test (skipped here);
detekt + Kover + strict dependency verification. Manual: run `--relikquary.storage.backend=s3` against
a Spaces/MinIO endpoint and publish/resolve.
