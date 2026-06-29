# Contract: Gradle Module Metadata & Browse Additions

Additive to the existing `/api` browse surface and the Maven wire protocol. The Maven contract,
publish/resolve, and authorization are unchanged (FR-006); these endpoints are read-only.

## Wire/protocol (unchanged, restated for clarity)

- `PUT /{repo}/{group}/{artifact}/{version}/{artifact}-{version}.module` (and its `.sha256`/`.asc`
  sidecars) is accepted and stored byte-for-byte, governed by the coordinate's existing release/snapshot
  immutability: a release `.module` is immutable (re-PUT ⇒ `409`), a snapshot `.module` is overwritable.
- `GET /{repo}/…/{artifact}-{version}.module` serves the stored bytes exactly.
- A proxy repo fetches and **caches** a versioned `.module` (it is not pass-through like
  `maven-metadata.xml`) and serves the cached copy on later requests.

## Browse: coordinate-aware contents

`GET /api/repositories/{repo}/contents/{group}/{artifact}/{version}` (repo READ)

```json
{
  "repository": "releases",
  "path": "com/acme/widget/1.2.3",
  "entries": [
    { "name": "widget-1.2.3.jar", "kind": "file", "size": 2048, "lastModified": "…" },
    { "name": "widget-1.2.3.module", "kind": "file", "size": 1024, "lastModified": "…" },
    { "name": "widget-1.2.3.pom", "kind": "file", "size": 512, "lastModified": "…" }
  ],
  "coordinate": { "group": "com.acme", "artifact": "widget", "version": "1.2.3" },
  "module": { "path": "com/acme/widget/1.2.3/widget-1.2.3.module" }
}
```

- `coordinate` is present when the path is a coordinate's version directory; `null` otherwise.
- `module` is present only when that directory contains a **recognized** `.module`
  (`{artifact}-{version}.module`); `null` for Maven-only coordinates.

## Browse: parsed module metadata (new)

`GET /api/repositories/{repo}/module/{group}/{artifact}/{version}/{artifact}-{version}.module` (repo READ)

```json
{
  "repository": "releases",
  "path": "com/acme/widget/1.2.3/widget-1.2.3.module",
  "parseable": true,
  "component": { "group": "com.acme", "module": "widget", "version": "1.2.3" },
  "variants": [
    {
      "name": "apiElements",
      "attributes": { "org.gradle.usage": "java-api", "org.gradle.category": "library" },
      "capabilities": [ { "group": "com.acme", "name": "widget", "version": "1.2.3" } ],
      "dependencies": [ { "group": "com.google.guava", "module": "guava", "version": "33.0.0-jre" } ],
      "files": [ { "name": "widget-1.2.3.jar", "url": "widget-1.2.3.jar", "size": 2048, "sha256": "…" } ]
    }
  ]
}
```

- **Not a module** (no recognized `.module` at the path) ⇒ `404`.
- **Malformed** `.module` (present but unparseable) ⇒ HTTP `200` with `{"parseable": false, "component":
  null, "variants": []}` — listing and download still work (FR-009).
- Output is derived purely from the stored bytes; the server never alters, re-checksums, or validates the
  `.module` (FR-011).

## Authorization

The `module` sub-resource is a repository **READ** (like `contents`/`file`): unauthenticated access to a
private repo's module endpoint ⇒ `401`, authenticated-without-read ⇒ `403`; open repos are readable.
Identical to the rest of the browse API (FR-006).

## Consume snippets (frontend-rendered, from `coordinate` + repo URL)

For coordinate `com.acme:widget:1.2.3` in repository `releases` at base `https://host`:

- **Gradle Kotlin DSL**: `repositories { maven { url = uri("https://host/releases") } }` +
  `implementation("com.acme:widget:1.2.3")`
- **Gradle Groovy DSL**: `repositories { maven { url 'https://host/releases' } }` +
  `implementation 'com.acme:widget:1.2.3'`
- **Maven XML**: a `<repository>` for `https://host/releases` + a `<dependency>` for the coordinate.

## Invariants

- Maven clients that ignore `.module` resolve exactly as before (FR-006).
- Recognition is coordinate-matching (`{artifact}-{version}.module`), not extension-only (FR-002).
- No secret or byte alteration; parsing is read-only (FR-011).
- Identical behaviour on filesystem and S3 (FR-010).
