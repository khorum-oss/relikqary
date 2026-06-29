# Research: Gradle Module Metadata & Gradle-First Browsing

Phase 0 decisions. The three product-level decisions were fixed in `/speckit-clarify` (backend parses GMM
→ structured API; recognition by coordinate-matching filename; snippets in Kotlin + Groovy + Maven). This
records the *how*, grounded in the current code.

## D0 — What already works (and must be preserved)

A code audit of the current backend shows the publish/resolve/proxy behaviour for `.module` is **already
correct**, because `.module` files ride the generic byte-faithful path:

- **Storage** preserves bytes exactly (`ArtifactStorage`/`FilesystemArtifactStorage`/`S3ArtifactStorage`);
  `.module` and its sidecars are stored/served like any file.
- **Publish immutability** (`RepositoryPath.classify()` + `RepublishPolicy`): a `.module` in a release
  version directory classifies as `RELEASE` ⇒ immutable; in a `-SNAPSHOT` directory ⇒ `SNAPSHOT` ⇒
  overwritable. This already satisfies FR-003 with **no change** to `RepublishPolicy`.
- **Proxy caching** (`RepositoryResolver.proxy`): only `maven-metadata.xml` (`PathKind.METADATA`) is
  pass-through; everything else — including a versioned `.module` — is cached and re-served. This already
  satisfies FR-005 with **no change**.

**Decision**: do not change `classify()`, `RepublishPolicy`, or `RepositoryResolver`'s caching rule. Add
recognition as an additive, separate concern and **lock the existing behaviour with tests** so it cannot
regress. This keeps the highest-risk paths untouched while delivering the "full fidelity" guarantee.

## D1 — `.module` recognition: coordinate-matching, on `RepositoryPath`

**Decision**: add `isModuleMetadata(): Boolean` to `RepositoryPath` (the existing validated coordinate
type), plus `artifactId`/`version` accessors derived from the key. A path is Gradle Module Metadata when
its file name ends with `.module` **and** its stem starts with `"{artifactId}-"`, where `artifactId` is
the path segment immediately above the version directory.

- Release: `com/acme/widget/1.2.3/widget-1.2.3.module` → artifactId `widget`, file starts with `widget-`
  and ends `.module` ⇒ recognized.
- Snapshot (unique/timestamped): `…/1.0-SNAPSHOT/widget-1.0-20260101.120000-1.module` → still starts with
  `widget-` and ends `.module` ⇒ recognized (the rule keys off the artifactId stem, not an exact version
  string, so timestamped snapshot module files match).
- A stray `notes.module` or `other-1.0.module` in a `widget` coordinate directory does **not** start with
  `widget-` ⇒ not recognized (avoids false positives, satisfying the clarified "filename matches the
  coordinate" rule).

**Rationale**: keeps recognition coordinate-aware (per clarification) without over-fitting the version
string; lives on the type that already owns path semantics; `classify()` stays unchanged so publish/proxy
decisions are unaffected.

**Alternatives considered**: a new `PathKind.MODULE` (would entangle the file-kind with the
release/snapshot immutability axis that `classify()` and `RepublishPolicy` depend on); extension-only
recognition (rejected in clarification — risks false positives).

## D2 — GMM model + tolerant parser

**Decision**: a `gradle/` package with immutable data classes modelling the parts of the Gradle Module
Metadata schema the UI needs, and a parser built on the already-present Jackson:

- Model: `GradleModuleMetadata(formatVersion, component(group/module/version), variants: List<Variant>)`;
  `Variant(name, attributes: Map<String,String>, capabilities: List<Capability>, dependencies:
  List<Dependency>, files: List<ModuleFile>)`; `Capability(group, name, version)`; `Dependency(group,
  module, version?)`; `ModuleFile(name, url, size?, sha256?)`. Attribute values are coerced to strings for
  display.
- Parser returns a sealed result: `Parsed(metadata)` or `Unparseable(reason)` — it **never throws** to the
  caller. Unknown JSON fields are ignored (`FAIL_ON_UNKNOWN_PROPERTIES=false`) so newer GMM format
  versions still parse the fields we present.

**Rationale**: a read-only projection of the published `.module` (clarified: backend parses). Tolerant
parsing satisfies FR-009's "malformed degrades gracefully." Jackson is already a dependency — no new dep.

**Alternatives considered**: depending on Gradle's own GMM parsing library (heavy, drags Gradle APIs onto
the server classpath — explicitly avoided elsewhere in this project); frontend parsing of the raw
`.module` (rejected in clarification).

## D3 — Browse API surface

**Decision**: two additions to the existing `/api` browse surface:

- **Contents become coordinate-aware**: when the listed path is a version directory that contains the
  coordinate's files, `ContentsResponse` includes `coordinate {group, artifact, version}` and, when a
  recognized `.module` is present, a `module {path}` reference. Both are null otherwise. The backend
  derives the coordinate from the prefix and detects the `.module` via `isModuleMetadata()`.
- **New endpoint** `GET /api/repositories/{repo}/module/**` (the `**` is the `.module` file path): reads
  the bytes, parses, and returns the structured `ModuleMetadataResponse` (variants → attributes/
  capabilities/dependencies/files). Returns `404` when the path is not a recognized module, and a
  graceful `{ parseable: false }` body (HTTP 200) when the `.module` exists but cannot be parsed.

**Rationale**: the contents `coordinate` lets the frontend render consume snippets for any coordinate
(Gradle module or not), and the `module` ref drives the badge + "view module" affordance; the dedicated
endpoint keeps parsing server-side and the listing cheap (no parse on every contents call).

**Alternatives considered**: embedding the full parsed module inside every `ContentsResponse` (parses on
every directory view — wasteful); a query param on `file` (overloads the file-details contract).

## D4 — Authorization of the new endpoint

**Decision**: extend `RepositoryAuthorizationManager.browseTarget` so a `GET` on the `module`
sub-resource maps to `Action.READ`, exactly like `contents`/`file`. Today only `contents`/`file` map to
READ and any other `/api/repositories/{repo}/...` shape is granted (open) — so without this change a
**private** repository's module endpoint would be readable without authorization (a feature-007
regression). Gating it as READ closes that.

**Rationale**: the module view exposes a repository's artifact metadata and must obey the same per-repo
read policy as browsing/file-details. Minimal, localized change to the existing manager.

**Alternatives considered**: leaving it open (leaks private-repo metadata — rejected); a separate filter
chain (unnecessary; the manager already models browse READ).

## D5 — Real Gradle round-trip with a feature variant (the fidelity proof)

**Decision**: the round-trip publisher is a `java-library` that **registers a feature with a capability**
(via `registerFeature`/an extra variant), so Gradle publishes a `.module` describing multiple variants
and a capability that **POM metadata cannot express**. The consumer build requests that capability; it can
only resolve if the `.module` was served faithfully and drove variant selection. Assert the resolved
artifact bytes match what was published.

- **Hosted** (US1/SC-001): publish to a hosted Relikquary repo, consume from a separate Gradle build.
- **Proxy** (US2/SC-002): a proxy repo whose upstream serves the published module (upstream = the hosted
  Relikquary repo, or a stub seeded with the published files); first resolve caches, second resolve is a
  cache hit; both byte-identical.

Uses the existing external-process Gradle harness (real `gradlew`, `--no-daemon`, fresh Gradle homes),
consistent with `PublishResolveRoundTripTest`/`ProxyRoundTripTest`. Docker-gated paths (MinIO) follow
existing practice; the default round-trip runs on filesystem here.

**Rationale**: a capability/feature variant is the canonical way to prove GMM actually round-tripped (not
a POM fallback) — it exercises FR-004 end-to-end with real clients (Principle II).

**Alternatives considered**: a plain single-variant `java-library` (Gradle may publish a `.module`, but
POM fallback resolves it too, so it doesn't *prove* GMM fidelity); asserting on `.module` bytes only
(weaker than proving a real consumer resolves variant-only content).

## D6 — Frontend presentation

**Decision**: three presentational Svelte components wired into the existing browse page:

- `GradleModuleBadge` — shown when `ContentsResponse.module` is present.
- `ConsumeSnippets` — given the `coordinate` and the repository URL (derived from the page origin +
  `/{repo}`), renders three copy-paste snippets behind a small tab/toggle: Gradle Kotlin DSL
  (`implementation("g:a:v")` + `maven { url = uri("…") }`), Gradle Groovy DSL (`implementation 'g:a:v'` +
  `maven { url '…' }`), and Maven `<dependency>` + `<repository>`.
- `ModuleDetail` — fetches `moduleMetadata(repo, modulePath)` and lists each variant with its attributes,
  capabilities, dependencies, and files; renders a graceful notice when the response is `parseable:false`.

**Rationale**: matches the clarified "both Gradle DSLs + Maven" and the backend-parsed detail view; keeps
the components thin over the typed API client; consistent with the existing SvelteKit component structure.

**Alternatives considered**: a separate module route (more navigation; the detail fits inline on the
coordinate's browse page); building snippets server-side (the frontend already knows origin + repo, so
client-side rendering avoids a round-trip and an extra contract).
