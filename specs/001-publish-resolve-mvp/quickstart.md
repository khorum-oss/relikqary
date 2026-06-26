# Quickstart & Validation: Core Publish-and-Resolve MVP

How to run Relikqary and prove the publish→resolve round-trip end-to-end. This is a validation guide;
implementation details live in `tasks.md` (after `/speckit-tasks`) and the code.

## Prerequisites

- JDK 21 (toolchain is provisioned by Gradle).
- This repository; the `backend` Spring Boot module.

## Run Relikqary locally

```bash
# Start the service with a chosen filesystem storage location
./gradlew :backend:bootRun \
  --args='--relikqary.storage.filesystem.root=/tmp/relikqary-store'
# Service listens on http://localhost:8080 (default)
```

To point at a different storage location, change `--relikqary.storage.filesystem.root` (or the
corresponding `application.yml` / env var) — no code change (validates FR-007 / SC-005).

## Manual round-trip (smoke test)

**1. Publish from a Gradle build.** In a throwaway library project:

```kotlin
// build.gradle.kts (publisher)
plugins { `java-library`; `maven-publish` }
group = "com.example"; version = "1.0.0"
publishing {
    publications { create<MavenPublication>("lib") { from(components["java"]) } }
    repositories { maven { url = uri("http://localhost:8080") ; isAllowInsecureProtocol = true } }
}
```

```bash
./gradlew publish        # expect BUILD SUCCESSFUL → SC-001
ls /tmp/relikqary-store/com/example/widget/1.0.0/   # files present, byte-identical
```

**2. Resolve with Maven.** In a consumer `pom.xml`, add the repository and the dependency:

```xml
<repositories>
  <repository><id>relikqary</id><url>http://localhost:8080</url></repository>
</repositories>
<dependency><groupId>com.example</groupId><artifactId>widget</artifactId><version>1.0.0</version></dependency>
```

```bash
mvn -U dependency:get -Dartifact=com.example:widget:1.0.0   # downloads + checksum verifies → SC-002
```

**3. Resolve with Gradle.** In a consumer `build.gradle.kts`:

```kotlin
repositories { maven { url = uri("http://localhost:8080") ; isAllowInsecureProtocol = true } }
dependencies { implementation("com.example:widget:1.0.0") }
```

```bash
./gradlew dependencies --configuration runtimeClasspath   # resolves → SC-003
```

**4. Verify byte-for-byte.** Compare the published jar's hash with the resolved jar's hash; they MUST
match (SC-004 / FR-003).

## Automated validation (authoritative — Principle II)

The MVP's correctness is proven by the round-trip integration test (see `plan.md`/`research.md` §7):

```bash
./gradlew :backend:test            # runs unit + @SpringBootTest round-trip
./gradlew build                    # full gate: compile + detekt + Kover verify + tests
```

The round-trip test boots Relikqary on a random port against a `@TempDir` store, then drives a **real**
Gradle publish (Gradle TestKit) and **real** Maven + Gradle resolves, asserting success and
byte-for-byte equality. `./gradlew build` must be green (detekt zero violations, Kover verification
passes) before merge.

## Edge-case checks to demonstrate

- GET an unpublished coordinate → `404`, and a client with Relikqary + another repo falls through
  (SC-006).
- Re-`publish` an existing RELEASE → `409`, stored files unchanged (SC-007); re-publish a `-SNAPSHOT`
  → overwrite succeeds.
