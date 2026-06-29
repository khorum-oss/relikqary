# Quickstart & Validation: First-Class Gradle Plugin Portal Support

End-to-end scenarios that prove the feature. Assumes the existing build/test toolchain (JDK 21, the repo's
Gradle wrapper). Implementation details live in `tasks.md`; this is a run/validation guide.

## Prerequisites

- Backend builds: `./gradlew :backend:build`.
- Network access to `https://plugins.gradle.org/m2/` for the live round-trip scenario (otherwise it
  auto-skips; the config assertion still runs).

## Scenario A — Default config ships the plugin-portal proxy (offline-safe, always runs)

Proves FR-001, FR-002, and the default-config contract.

1. Run the default-config assertion test (`DefaultRepositoriesTest`):
   ```bash
   ./gradlew :backend:test --tests '*DefaultRepositoriesTest*'
   ```
2. **Expected**: passes — a `gradle-plugins` proxy exists with upstream `https://plugins.gradle.org/m2/`,
   and the default `public` group's members are exactly `[releases, maven-central, gradle-plugins]` in order.

## Scenario B — Real Gradle resolves a plugin through Relikquary (guarded, live)

Proves FR-003/004/005, SC-001, SC-003, and User Story 1.

1. Run the guarded round-trip:
   ```bash
   ./gradlew :backend:test --tests '*GradlePluginPortalRoundTripIT*'
   ```
2. The test boots the app with default config, then runs a real Gradle build whose `settings.gradle.kts`
   points `pluginManagement` at the test server's `…/public`, applying a small stable plugin.
3. **Expected (online)**: the Gradle build configures successfully; the plugin marker POM and implementation
   artifact are now present in the proxy cache under `gradle-plugins/…` (byte-faithful). **Expected
   (offline)**: the test reports *skipped* (assumption not met), not failed.

## Scenario C — Cache hit / offline resolution

Proves FR-004 and User Story 2.

1. After Scenario B has populated the cache, stop external network access (or point
   `RELIKQUARY_GRADLE_PLUGIN_PORTAL_URL` at an unreachable host) and re-resolve the same plugin through
   `…/public`.
2. **Expected**: resolution still succeeds from cache (no upstream contact). A *different*, never-cached
   plugin coordinate returns `502` (upstream error), distinct from a `404` for a genuinely nonexistent
   coordinate (SC-006).

## Scenario D — Override the portal upstream

Proves FR-007, SC-004, User Story 3.

1. Start the server with `RELIKQUARY_GRADLE_PLUGIN_PORTAL_URL=https://my-mirror.example/m2/`.
2. **Expected**: plugin requests through `gradle-plugins`/`public` are sent to the mirror. Unset the
   variable and restart → requests go to `https://plugins.gradle.org/m2/` (the documented default).

## Scenario E — Read-only enforcement & no regressions

Proves FR-009, FR-010, SC-005.

1. `PUT` to `…/gradle-plugins/…` (or `…/public/…`) → **Expected**: `405 Method Not Allowed`,
   `Allow: GET, HEAD`.
2. Run the full backend build: `./gradlew :backend:build`.
3. **Expected**: green — detekt zero, Kover thresholds hold, strict dependency verification unchanged (no
   new deps), and all pre-existing proxy/group/publish/resolve/auth tests still pass.

## Manual smoke (optional)

Point a throwaway Gradle project's `settings.gradle.kts` `pluginManagement` at a running Relikquary's
`/public`, apply a community plugin via the `plugins { }` block, and run `./gradlew help` — it should
configure without reaching `plugins.gradle.org` directly (after the first resolve, even offline).
