---
description: "Task list for Publish Authentication & Authorization"
---

# Tasks: Publish Authentication & Authorization

**Input**: Design documents from `specs/002-publish-auth/`

**Tests**: Included (constitution Principle II). Paths are repository-relative under the `backend` module.

## Phase 1: Setup

- [x] T001 Add `spring-boot-starter-security` to `gradle/libs.versions.toml` and `backend/build.gradle.kts`

## Phase 2: Foundational

- [x] T002 [P] Add `config/SecurityProperties.kt` (`@ConfigurationProperties("relikqary.security")`: `enabled: Boolean = true`, `users: List<User>` of username/password/roles)
- [x] T003 Add `config/SecurityConfig.kt`: `PasswordEncoder` (delegating), `UserDetailsService` (in-memory from props), and a `SecurityFilterChain` branching on `enabled` (enabled ⇒ httpBasic + `PUT` requires `hasRole("PUBLISH")`, else permitAll, csrf off, stateless; disabled ⇒ permitAll) — depends on T001, T002
- [x] T004 Register `SecurityProperties` in `RelikqaryApplication.kt` `@EnableConfigurationProperties`; document keys in `application.yml`; add `application-local.yml` (`local` profile: `relikqary.security.enabled: false` + local storage root) — depends on T002

## Phase 3: US1 — Only authenticated publishers can publish (P1)

- [x] T005 [P] [US1] Integration test `integration/AuthPublishTest.kt` (`@SpringBootTest` RANDOM_PORT + `HttpClient`, a configured `{noop}` publisher via `@DynamicPropertySource`): `PUT` no-cred → 401; wrong-cred → 401; valid publisher → 201; authenticated non-publisher → 403
- [x] T006 [P] [US1] Unit test `unit/SecurityPropertiesTest.kt` (binding + roles mapping)

## Phase 4: US2 — Resolving stays open (P1)

- [x] T007 [US1/US2] Integration assertion (in AuthPublishTest): `GET` of a published artifact with no credentials → 200 while auth enabled
- [x] T008 [US2] Update `integration/PublishResolveRoundTripTest.kt`: configure a publisher, add `credentials { }` to the publisher build, assert the credentialed publish succeeds and anonymous Maven+Gradle resolve still works; add a negative check that a no-credential publish fails (401)

## Phase 5: US3 — Disable auth for local dev (P2)

- [x] T009 [P] [US3] Integration test `integration/AuthDisabledTest.kt` (`relikqary.security.enabled=false`): `PUT` no-cred → 201

## Phase 6: Polish

- [x] T010 Regenerate `gradle/verification-metadata.xml` (SHA-256) for the new Spring Security deps (`./gradlew --write-verification-metadata sha256 build`)
- [x] T011 [P] Update `README.md` and `specs/002-publish-auth/quickstart.md`: local run (auth off) and authenticated run (configure publisher, publish with credentials)
- [x] T012 `./gradlew build` green (compile + detekt + tests + Kover + strict verification); commit & push

## Dependencies

Setup → Foundational → US1/US2 (P1) → US3 (P2) → Polish. T008 depends on the SecurityConfig (T003)
and the configured publisher. T010 after the dependency set is stable.
