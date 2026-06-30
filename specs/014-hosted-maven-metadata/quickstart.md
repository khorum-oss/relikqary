# Quickstart & Validation: Server-Side Hosted maven-metadata.xml

Scenarios proving the feature. Uses the project's Gradle wrapper and test toolchain (JDK 21; real
Maven/Gradle via the external-process round-trip harness for the client scenarios).

## Prerequisites

- `./gradlew :backend:build` green.
- For the round-trip scenario: the runner's `mvn` and the repo's `gradlew` (as in the existing
  `PublishResolveRoundTripTest`).

## Scenario A — Independent publishers don't clobber the listing (the core fix)

Proves FR-001/FR-002/FR-007, SC-001/SC-002.

1. Publish `com.example:widget:1.0.0` (PUT the jar + pom + a `maven-metadata.xml` listing only 1.0.0).
2. As a *different* publisher, publish `com.example:widget:2.0.0` (PUT jar + pom + a `maven-metadata.xml`
   listing only 2.0.0 — simulating a client that never saw 1.0.0).
3. GET `…/com/example/widget/maven-metadata.xml`.

**Expected**: the served metadata lists **both** 1.0.0 and 2.0.0; `<release>` = 2.0.0, `<latest>` = 2.0.0,
`<lastUpdated>` advanced; the served `.sha1`/`.md5` match the served bytes.

## Scenario B — Snapshot resolution across builds

Proves FR-004, SC-003.

1. Publish two timestamped builds of `com.example:widget:3.0.0-SNAPSHOT` (canonical
   `YYYYMMDD.HHMMSS-N` filenames), optionally from two clients.
2. GET the version-level `…/widget/3.0.0-SNAPSHOT/maven-metadata.xml`.

**Expected**: `<snapshot>` points at the **newest** build's timestamp/buildNumber; `<snapshotVersions>` lists
that build's artifacts; the artifact-level listing includes `3.0.0-SNAPSHOT`.

## Scenario C — Mixed repo and per-type correctness

Proves FR-005, US3.

1. In a mixed hosted repo, publish a release and a snapshot of the same artifact.
2. GET the artifact-level metadata.

**Expected**: both appear; `<release>` = the release, `<latest>` = the newest overall.

## Scenario D — Proxy metadata untouched

Proves FR-006, SC-005.

1. GET `maven-metadata.xml` through a proxy repo (e.g. `maven-central`).

**Expected**: served from upstream as before — not generated or overridden by this feature (existing
proxy/group tests still pass).

## Scenario E — Real Maven + Gradle round-trip

Proves FR-009, FR-012, SC-003.

```bash
./gradlew :backend:test --tests '*HostedMetadataRoundTripTest*'
```
**Expected**: a real Maven client and a real Gradle client resolve a version range / `latest` and a
`-SNAPSHOT` correctly against multi-version, multi-publisher state.

## Scenario F — Checksums & no regressions

Proves FR-003/FR-008/FR-010, SC-004/SC-006.

```bash
./gradlew :backend:build
```
**Expected**: green — the new metadata unit + HTTP + round-trip tests pass; detekt zero; Kover holds; strict
dependency verification unchanged (no new deps); all pre-existing publish/resolve/auth/proxy/group/cleanup
tests still pass; no non-metadata artifact is altered.
