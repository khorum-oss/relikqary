# Phase 0 Research: First-Class Gradle Plugin Portal Support

All decisions reuse feature 006's proxy/group implementation; the open questions are about defaults,
client contract, and how to verify against the real portal. No NEEDS CLARIFICATION remained from the spec.

## D1 — Upstream endpoint for the Gradle Plugin Portal

**Decision**: Use `https://plugins.gradle.org/m2/` as the default `remoteUrl` for the `gradle-plugins`
proxy, overridable via `RELIKQUARY_GRADLE_PLUGIN_PORTAL_URL`.

**Rationale**: The Gradle Plugin Portal publishes a standard **Maven-layout** mirror at `/m2/`. A Gradle
client resolving a plugin against a `maven { }` repository fetches the marker POM
`{id}/{id}.gradle.plugin/{ver}/{id}.gradle.plugin-{ver}.pom` and the implementation artifacts using the
exact path scheme the existing `proxy` kind already speaks. No portal-specific protocol is involved, so the
feature-006 `UpstreamClient`/`RepositoryResolver` handle it with zero code change.

**Alternatives considered**:
- The Plugin Portal's native plugin-resolution API (non-Maven). Rejected: it is not a Maven-layout
  repository, would need a new client/protocol, and the `/m2/` endpoint is exactly what `maven { }` plugin
  repositories consume — clients already treat it as Maven.

## D2 — Reuse `proxy` kind vs new "plugin" kind

**Decision**: Reuse the existing `proxy` `RepositoryKind`. No new kind, no new class.

**Rationale**: Plugin marker POMs and implementation artifacts are ordinary Maven-layout artifacts. The
proxy cache-first + metadata-pass-through behavior is exactly correct for them. Introducing a kind would add
surface area and a second resolution path for no behavioral gain.

**Alternatives considered**: A dedicated `plugin` kind that special-cases marker coordinates. Rejected: the
marker is already just a POM at a normal coordinate; nothing about it needs special handling server-side.

## D3 — Default group membership and ordering

**Decision**: Append `gradle-plugins` to the existing default `public` group as the **last** member:
`public.members = [releases, maven-central, gradle-plugins]` (per /speckit-clarify).

**Rationale**: First-match resolution means a request is tried against members in order. Placing the portal
last keeps the common case fast: an ordinary dependency absent from `releases` is served by `maven-central`
without ever touching the portal. A plugin marker/impl that Central does not have falls through to the
portal. Local (`releases`) and Central artifacts win on the rare coordinate collision, which is the
conservative choice. Existing members keep their relative order, so no previously-resolvable coordinate
changes source.

**Alternatives considered**:
- Portal before Central — adds a portal 404 round-trip to every Central-bound dependency miss; slower for
  the dominant case. Rejected.
- A separate `gradle-plugins`-only group/endpoint instead of joining `public` — splits client config into
  two URLs and contradicts the "single endpoint" intent. The standalone proxy is still addressable at
  `/gradle-plugins` for operators who want to target it directly, so nothing is lost. Rejected as the default.

## D4 — Client-side wiring (out of server scope, documented)

**Decision**: Document, but do not automate, the client `settings.gradle.kts` snippet that points
`pluginManagement { repositories { maven { url } } }` (and `dependencyResolutionManagement`) at `…/public`.

**Rationale**: The server cannot change how a client is configured; making the portal resolvable by default
is the server's job, and pointing the build at Relikquary is the developer's. This matches feature 006,
where the server proxies Central but the build must still declare the Relikquary URL. Clear docs close the
gap. Note `pluginManagement` must be declared in `settings.gradle.kts` (it is evaluated before the build
script), which the docs will state explicitly.

## D5 — Verification strategy (real Gradle round-trip, guarded)

**Decision**: Add `GradlePluginPortalRoundTripIT` — boot the app with default config, drive a **real Gradle**
build (external process, reusing the existing round-trip harness) whose `pluginManagement` points at the
test server's `…/public`, applying a small, stable, widely-used plugin; assert the build configures
successfully and that the marker POM + implementation artifact are now cached under `gradle-plugins/…`.
**Guard**: probe portal reachability first and `Assumptions`-skip (JUnit `assumeTrue`) when offline, exactly
as `ProxyCentralIT` does for Central. Also add a fast `DefaultRepositoriesTest` asserting the shipped default
contains a `gradle-plugins` proxy with the portal URL and that `public` lists it last — this runs always
(offline-safe) and locks the default config contract.

**Rationale**: Constitution II requires real client round-trips; the portal is an external service, so the
end-to-end test must be guarded to stay green offline/air-gapped (the same justified pattern as 006's
Central test). The always-on config assertion guarantees the *default shipped behavior* is verified even
when the network test skips.

**Plugin choice**: a tiny, stable, no-extra-dependency plugin to keep the download small and the test fast
and robust — candidate `org.gradle.hello-world`-style is unavailable, so use a well-known community plugin
such as `com.diffplug.spotless` or `org.jetbrains.gradle.plugin.idea-ext`. Final pick made at implement time
based on smallest reliable footprint; the test only needs the plugin to *apply*, not to do work.

**Alternatives considered**: A local stub portal (like 006's stub upstream). Kept for the *unit/integration*
correctness of proxy behavior (already covered by 006 tests); but FR-011 explicitly requires a **real**
Gradle-against-real-portal round-trip, so the guarded live test is mandatory here.

## D6 — Metadata, immutability, and caching semantics

**Decision**: Inherit feature-006 proxy semantics verbatim: immutable plugin artifacts (marker POM, impl
jar/POM, checksums, signatures) cache permanently; `maven-metadata.xml` (and other Maven metadata) is
pass-through, never cached.

**Rationale**: A plugin version's artifacts are immutable, so permanent caching is correct and enables the
offline story (US2). Version listings change as new plugin versions publish, so metadata must stay fresh.
This is precisely the existing proxy contract — no new logic.

**Alternatives considered**: Time-boxed caching of metadata. Rejected: pass-through is already correct and
consistent; adding TTLs would diverge from the established proxy behavior for no benefit here.
