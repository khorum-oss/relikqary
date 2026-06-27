# Implementation Plan: Publish Authentication & Authorization

**Feature**: `002-publish-auth` (tracked via `.specify/feature.json`; developed on git branch `claude/spec-002-auth`) | **Date**: 2026-06-26 | **Spec**: [spec.md](./spec.md)

## Summary

Add Spring Security HTTP Basic auth in front of the existing `RepositoryController`: `PUT` requires a
configured publisher; `GET`/`HEAD` stay open. Users come from static configuration with bcrypt
passwords. A single `relikquary.security.enabled` flag (default true) disables auth for local dev,
wired through a `local` Spring profile. Stored bytes and the Maven wire layout are unchanged.

## Technical Context

**Language/Version**: Kotlin 2.3.21 / JDK 21 (unchanged). **Primary Dependencies**: add
`spring-boot-starter-security` (Spring Security 7, managed by the Spring Boot 4.1 BOM).
**Testing**: JUnit Jupiter + `@SpringBootTest` + JDK `HttpClient`; real-client round-trip via external
`gradlew`/`mvn` (unchanged harness). **Project Type**: web service (single `backend` module).

## Constitution Check

- **I. Repository Contract & Client Compatibility** — PASS. HTTP Basic is spoken by stock Maven/Gradle;
  wire layout and stored bytes unchanged (FR-009). Round-trip re-validated with credentials.
- **II. Test-First & Integration-Verified** — PASS. Real Gradle publish *with* and *without*
  credentials, plus anonymous resolve, are integration-tested.
- **III. Quality Gates** — PASS. detekt/Kover stay enforced; build cleanup not needed.
- **IV. Supply-Chain Integrity & Faithful Storage** — PASS. New deps added to
  `verification-metadata.xml` (regenerate SHA-256); auth never mutates stored bytes.

No violations; Complexity Tracking empty.

## Project Structure

New under `backend/src/main/kotlin/org/khorum/oss/relikquary/config/`:
`SecurityProperties.kt` (`enabled` + users), `SecurityConfig.kt` (`SecurityFilterChain`,
`PasswordEncoder`, `UserDetailsService`). New resource `application-local.yml`. Tests under
`backend/src/test/.../integration/` and `.../unit/`.

**Structure Decision**: Reuse the single `backend` module and the `config/` package; mirror the
existing `StorageProperties`/`PublishProperties` configuration-properties pattern.

## Phase 0 — Decisions (research)

- **Scheme**: HTTP Basic (client compatibility) over WebFlux-free Spring MVC; stateless, CSRF off.
- **User store**: in-memory `UserDetailsService` built from config (`InMemoryUserDetailsManager`),
  `DelegatingPasswordEncoder` for `{bcrypt}`/`{noop}`.
- **Authorization**: `PUT /**` ⇒ `hasRole("PUBLISH")`; everything else `permitAll`.
- **Disable path**: branch the `SecurityFilterChain` on `SecurityProperties.enabled`; disabled ⇒
  `permitAll` for all. `local` profile sets the flag off.
- **Confirm at implement time**: exact Spring Security 7 lambda-DSL API against the BOM version.

## Phase 1 — Design

- **Entities**: Publisher User (username, encoded password, roles), Security Settings (enabled, users)
  — both bound from `relikquary.security.*`.
- **Contract delta** (to `contracts/`): `PUT` gains `401`/`403` responses when auth enabled; `GET`/
  `HEAD` unchanged. Documented in `contracts/auth.md`.

## Verification

See spec Success Criteria. Gate: `./gradlew build` green (compile + detekt + tests + Kover + strict
dependency verification). Local run: `./gradlew :backend:bootRun --args='--spring.profiles.active=local'`
→ open server, publish without login.
