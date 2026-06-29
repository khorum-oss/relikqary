# Quickstart: Gradle Module Metadata & Gradle-First Browsing

Validates feature 011. See [contracts/module-api.md](contracts/module-api.md) and
[data-model.md](data-model.md) for the full contract.

## Prerequisites

- JDK 21; `./gradlew :backend:bootJar`. A hosted `releases` repo and a `maven-central`-style proxy.
- A publisher Gradle build that produces Gradle Module Metadata with a feature variant/capability, e.g.:

```kotlin
plugins { `java-library`; `maven-publish` }
group = "com.acme"; version = "1.2.3"
java { registerFeature("extra") { usingSourceSet(sourceSets["main"]) } } // adds a capability variant
publishing {
  publications { create<MavenPublication>("lib") { from(components["java"]) } }
  repositories { maven { url = uri("http://localhost:8080/releases"); isAllowInsecureProtocol = true
    credentials { username = "ci"; password = "secret" } } }
}
```

(`maven-publish` from a software component publishes the `.module` by default; the feature adds a
capability that only Gradle Module Metadata can express.)

## Scenario 1 — Gradle module round-trips on a hosted repo (US1)

```bash
./gradlew -p publisher publish        # publishes widget-1.2.3.jar/.pom/.module (+ feature variant jar)
```

Then a consumer build depends on the capability and resolves through `releases`:

```kotlin
repositories { maven { url = uri("http://localhost:8080/releases") } }
dependencies { implementation("com.acme:widget:1.2.3") { capabilities { requireCapability("com.acme:widget-extra") } } }
```

Expected: resolution succeeds (only possible via the `.module`), and the resolved files are byte-for-byte
identical to what was published. A re-publish of the release `.module` is rejected (`409`); a snapshot
version's `.module` is overwritable.

## Scenario 2 — Through a proxy (US2)

Point a proxy repo's upstream at the hosted `releases` (or a stub seeded with the published files), then
resolve the same capability through the proxy. Expected: the first resolve fetches and caches the
`.module` and artifacts; a second resolve is a cache hit; both byte-identical and variant-selecting.

## Scenario 3 — Browse UI surfaces the module (US3)

```bash
# Coordinate-aware contents: coordinate + module ref present.
curl -s http://localhost:8080/api/repositories/releases/contents/com/acme/widget/1.2.3 | jq '.coordinate, .module'

# Parsed module metadata: variants with attributes/capabilities/deps/files.
curl -s http://localhost:8080/api/repositories/releases/module/com/acme/widget/1.2.3/widget-1.2.3.module | jq '.variants[].name'
```

In the web UI, browse to the coordinate: it shows a **Gradle module** badge, copy-paste consume snippets
for Gradle Kotlin DSL, Gradle Groovy DSL, and Maven, and a module detail view listing each variant's
attributes, capabilities, dependencies, and files. A Maven-only coordinate shows no badge/detail but still
offers snippets and browses normally. A malformed `.module` still browses and downloads (detail degrades).

## Scenario 4 — Maven contract unchanged

A plain Maven (or POM-based Gradle) resolve of the same coordinate works exactly as before; a private
repo's `/module` endpoint requires read authorization (`401`/`403`).

## Automated verification

```bash
./gradlew build      # backend: detekt + Kover + unit (GMM parser, recognition) + integration (module
                     # browse, authz) + the real Gradle feature-variant round-trip (hosted + proxy);
                     # existing Maven/Gradle POM round-trips unchanged; verification-metadata untouched
cd frontend && npm run build && npm test   # badge, consume snippets, module detail
```

Expected: green; the round-trip drives real Gradle clients over the HTTP + storage boundary.
