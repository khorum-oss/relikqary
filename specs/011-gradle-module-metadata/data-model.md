# Data Model: Gradle Module Metadata & Gradle-First Browsing

Feature 011 adds a read-only projection of published Gradle Module Metadata and coordinate awareness to
the browse API. No persistent data is added; stored `.module` bytes are never altered.

## Recognition (on `RepositoryPath`)

`RepositoryPath` (existing `@JvmInline value class`) gains, derived purely from the key:

| Member | Type | Meaning |
|--------|------|---------|
| `artifactId` | `String` | The path segment immediately above the version directory. |
| `version` | `String` | The version directory segment. |
| `isModuleMetadata()` | `Boolean` | `fileName` ends with `.module` **and** starts with `"{artifactId}-"`. |

`classify()` is unchanged (`RELEASE`/`SNAPSHOT`/`METADATA`), so publish immutability and proxy caching are
unaffected: a release `.module` stays immutable, a snapshot `.module` overwritable, and a versioned
`.module` is cached by the proxy (not pass-through).

## Parsed Gradle Module Metadata (`gradle/` package)

A read-only model of the parts of the GMM JSON the UI presents. Parsed on demand; never written back.

### `GradleModuleMetadata`

| Field | Type | Meaning |
|-------|------|---------|
| `formatVersion` | `String` | GMM format version string (e.g. `1.1`). |
| `component` | `Component` | The published component coordinate. |
| `variants` | `List<Variant>` | The module's variants. |

### `Component`

| Field | Type | Meaning |
|-------|------|---------|
| `group` | `String` | Group id. |
| `module` | `String` | Module (artifact) name. |
| `version` | `String` | Version. |

### `Variant`

| Field | Type | Meaning |
|-------|------|---------|
| `name` | `String` | Variant name (e.g. `apiElements`, `runtimeElements`). |
| `attributes` | `Map<String,String>` | Variant attributes (values coerced to string for display). |
| `capabilities` | `List<Capability>` | Declared capabilities. |
| `dependencies` | `List<Dependency>` | The variant's dependencies. |
| `files` | `List<ModuleFile>` | The files the variant produces. |

### `Capability` / `Dependency` / `ModuleFile`

| Type | Fields |
|------|--------|
| `Capability` | `group`, `name`, `version` |
| `Dependency` | `group`, `module`, `version?` |
| `ModuleFile` | `name`, `url`, `size?` (Long), `sha256?` |

### Parse result

`ParseResult` — `Parsed(metadata: GradleModuleMetadata)` or `Unparseable(reason: String)`. The parser
never throws to callers; unknown JSON fields are ignored so newer format versions still parse.

## Browse API DTO additions

### `Coordinate`

| Field | Type | Meaning |
|-------|------|---------|
| `group` | `String` | Dotted group id derived from the path. |
| `artifact` | `String` | Artifact id. |
| `version` | `String` | Version. |

### `ModuleRef`

| Field | Type | Meaning |
|-------|------|---------|
| `path` | `String` | Repository-relative path of the recognized `.module` file. |

### `ContentsResponse` (modified)

| Field | Type | Meaning |
|-------|------|---------|
| `repository` | `String` | (existing) |
| `path` | `String` | (existing) |
| `entries` | `List<ListingEntry>` | (existing) |
| `coordinate` | `Coordinate?` | Present when the path is a coordinate's version directory; else null. |
| `module` | `ModuleRef?` | Present when that directory contains a recognized `.module`; else null. |

### `ModuleMetadataResponse` (new endpoint body)

| Field | Type | Meaning |
|-------|------|---------|
| `repository` | `String` | Repository name. |
| `path` | `String` | The `.module` path. |
| `parseable` | `Boolean` | False when the `.module` could not be parsed (graceful degrade). |
| `component` | `Component?` | The component coordinate (null when not parseable). |
| `variants` | `List<Variant>` | The parsed variants (empty when not parseable). |

## Endpoints

| Endpoint | Method | Auth | Purpose |
|----------|--------|------|---------|
| `/api/repositories/{repo}/contents/**` | GET | repo READ | Listing, now with `coordinate`/`module` when applicable. |
| `/api/repositories/{repo}/module/**` | GET | repo READ (new gate) | Parsed module metadata, or `404`, or graceful `parseable:false`. |

## Frontend types (mirror the DTOs)

`api.ts` gains `Coordinate`, `ModuleRef`, `ModuleMetadata` (with `Variant`/`Capability`/`Dependency`/
`ModuleFile`), the new `coordinate`/`module` fields on `ContentsResponse`, and `moduleMetadata(repo,
path)`.

## Lifecycle & invariants

- Recognition and parsing are read-only; the `.module` bytes are served unchanged (FR-011, Principle IV).
- Parsing occurs only on the module endpoint, never on publish/resolve.
- The module endpoint obeys the same per-repository READ policy as the rest of the browse API (FR-006).
- A malformed `.module` yields `parseable:false` and never breaks listing/download (FR-009).
- Behaviour is identical on filesystem and S3 (FR-010).
