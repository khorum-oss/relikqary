# Feature Specification: First-Class Gradle Plugin Portal Support

**Feature Branch**: `claude/spec-012-gradle-plugin-portal`

**Created**: 2026-06-29

**Status**: Draft

**Input**: User description: "First-class Gradle Plugin Portal support. Relikquary already proxies and caches Maven-layout repositories (feature 006: proxy + group repository kinds, with Maven Central shipped as the default proxy upstream). Gradle builds, however, resolve their plugins through the Gradle Plugin Portal (plugins.gradle.org), which clients reach via its Maven-layout endpoint at https://plugins.gradle.org/m2/ — resolving a plugin marker artifact ({plugin.id}:{plugin.id}.gradle.plugin:{version}, a POM) plus the implementation artifact it points to. Today an operator can hand-configure a proxy repo for this, but it is neither shipped by default nor verified. This feature makes the Gradle Plugin Portal a first-class, out-of-the-box proxied upstream."

## Clarifications

### Session 2026-06-29

- Q: How should the Gradle Plugin Portal proxy be exposed so Gradle builds can reach it? → A: Add a `gradle-plugins` proxy and make it a member of the existing default `public` group (single endpoint resolves both dependencies and plugins); the proxy is also addressable on its own as `/gradle-plugins` like every named repository.
- Q: Where should the plugin-portal proxy sit in the `public` group's resolution order (first match wins)? → A: Last — `public = [releases, maven-central, gradle-plugins]`, so local and Central artifacts win and the portal is consulted only on a miss elsewhere.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Resolve a Gradle plugin through Relikquary out of the box (Priority: P1)

A developer points their Gradle build's plugin resolution at a freshly started Relikquary server (its default aggregate group URL) and applies a community plugin (for example `com.diffplug.spotless`) using the standard `plugins { }` block. Without the operator hand-editing any repository configuration, Relikquary resolves the plugin marker and its implementation from the Gradle Plugin Portal, caches them, and serves them back so the build succeeds.

**Why this priority**: This is the entire point of the feature — a Gradle build cannot even configure itself if its plugins do not resolve. Maven Central proxying (feature 006) already lets dependency resolution work through Relikquary; the missing piece for a Gradle-first audience is plugins. Delivering just this story makes Relikquary usable as the single repository endpoint a Gradle build needs.

**Independent Test**: Configure a real Gradle build's plugin repositories to a clean Relikquary's default group URL, apply a real published plugin, and confirm the build resolves it (marker POM + implementation artifact) successfully with no upstream-specific repository added by hand.

**Acceptance Scenarios**:

1. **Given** a freshly started server with default configuration and an empty cache, **When** a Gradle build requests a plugin marker artifact (`{plugin.id}:{plugin.id}.gradle.plugin`) through the default aggregate group, **Then** the server fetches it from the plugin portal upstream, caches it, and returns it.
2. **Given** the marker has been resolved, **When** the build requests the plugin's implementation artifact (the coordinate the marker points to), **Then** the server resolves it (from the plugin portal or another configured upstream) and the build applies the plugin successfully.
3. **Given** a plugin's artifacts were previously cached, **When** the same artifacts are requested again, **Then** the server serves them from its cache without contacting any upstream.

---

### User Story 2 - Continue building when the Plugin Portal is unreachable (Priority: P2)

A developer on a restricted or offline network runs a build whose plugins were previously resolved (and therefore cached) through Relikquary. Even though the Gradle Plugin Portal cannot be reached, the build still resolves those plugins from Relikquary's cache.

**Why this priority**: Offline/air-gapped resilience is a primary reason teams run their own repository manager. It builds directly on P1 (artifacts must be cached first) and extends the existing proxy guarantee to plugin artifacts, but the server is still useful without it, so it ranks second.

**Independent Test**: Resolve a plugin once through Relikquary, then make the upstream unreachable and resolve the same plugin again; confirm it still succeeds from cache.

**Acceptance Scenarios**:

1. **Given** a plugin's immutable artifacts are cached, **When** the upstream plugin portal is unreachable and the build requests those artifacts, **Then** the server serves the cached copies and the build succeeds.
2. **Given** an artifact has never been cached, **When** the upstream is unreachable and it is requested, **Then** the server reports an upstream error distinct from "not found", so the client can tell a connectivity problem from a missing artifact.

---

### User Story 3 - Operator points the Plugin Portal proxy elsewhere (Priority: P3)

An operator running in an environment that cannot reach `plugins.gradle.org` directly (for example, behind a corporate mirror of the portal) overrides the upstream URL for the plugin-portal proxy via configuration or an environment variable, without changing anything else.

**Why this priority**: Configurability matters for real deployments but the shipped default already serves the majority case; this is a refinement on top of P1.

**Independent Test**: Override the plugin-portal upstream URL via an environment variable, start the server, and confirm plugin resolution goes to the overridden location (and the default applies when the variable is unset).

**Acceptance Scenarios**:

1. **Given** no override is provided, **When** the server starts, **Then** the plugin-portal proxy uses the documented safe default upstream URL.
2. **Given** the upstream URL is overridden via environment variable, **When** the server starts and a plugin is requested, **Then** the request is sent to the overridden upstream.

---

### Edge Cases

- **Plugin also published to Maven Central**: When a requested plugin marker or implementation exists on more than one member of the aggregate group, the group's existing first-match ordering decides which upstream serves it; the result is still a faithful copy. Each upstream's artifacts are cached under their own proxy namespace, so there is no name collision.
- **Plugin marker exists but implementation is missing upstream**: The marker resolves but the implementation request returns "not found"; the build fails with a not-found error (not an upstream/connectivity error), matching how the portal itself behaves.
- **Unknown plugin / unknown version**: A request for a non-existent plugin coordinate returns "not found" and nothing is cached.
- **Snapshot or changing plugin metadata**: Maven-style metadata (e.g. `maven-metadata.xml`) is always served fresh from upstream (pass-through), never cached, consistent with existing proxy behavior, so newly published plugin versions become visible without restarting or clearing the cache.
- **Publishing attempt**: A `PUT`/upload to the plugin-portal proxy (or to the read-only aggregate group) is rejected as not allowed, consistent with existing proxy/group behavior.
- **Upstream redirects**: The upstream may respond with redirects; the server follows them so resolution still succeeds.
- **Plugin portal not reachable at build-configuration time and not cached**: The build fails to configure; the server surfaces an upstream error so the cause is diagnosable.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST ship, in its default configuration, a proxy repository whose upstream is the Gradle Plugin Portal's Maven-layout endpoint, so that plugin resolution works out of the box without operator configuration.
- **FR-002**: The system MUST include the plugin-portal proxy as a member of the default aggregate (group) repository, so a Gradle build pointed at a single default endpoint can resolve both ordinary dependencies and plugins.
- **FR-003**: On a cache miss for an immutable plugin artifact (marker POM or implementation artifact), the system MUST fetch it from the configured plugin-portal upstream, store it, and serve it.
- **FR-004**: The system MUST serve previously cached plugin artifacts on subsequent requests without contacting the upstream.
- **FR-005**: The system MUST preserve fetched plugin artifacts byte-for-byte (including their checksums and any signatures), so client-side verification succeeds exactly as it would against the portal directly.
- **FR-006**: The system MUST treat plugin metadata files the same as other proxy metadata — always served fresh from upstream (pass-through), never cached.
- **FR-007**: The plugin-portal upstream URL MUST be configurable, with a documented safe default, and MUST be overridable via an environment variable, consistent with how the existing Maven Central proxy upstream is configured.
- **FR-008**: When the plugin-portal upstream is unreachable, the system MUST serve any cached artifacts normally, and for uncached artifacts MUST report an upstream error that is distinguishable from a "not found" result.
- **FR-009**: The plugin-portal proxy MUST be read-only: attempts to publish/upload to it (or to the read-only aggregate group) MUST be rejected as not allowed.
- **FR-010**: Existing publish, resolve, proxy, group, and authentication/authorization behavior, and the existing Maven client contract, MUST remain unchanged; this feature is additive.
- **FR-011**: The system MUST be verified by a real Gradle client resolving a real published plugin through the proxy end-to-end (round-trip), and that verification MUST automatically skip when the upstream cannot be reached (offline), mirroring the existing proxy round-trip verification.
- **FR-012**: The default configuration and the configuration/override mechanism for the plugin-portal upstream MUST be documented for operators.

### Key Entities *(include if feature involves data)*

- **Gradle Plugin Portal proxy repository**: A proxied, read-only repository whose upstream is the plugin portal's Maven-layout endpoint; named distinctly from the existing Maven Central proxy and addressable on its own.
- **Plugin marker artifact**: A POM published at coordinate `{plugin.id}:{plugin.id}.gradle.plugin:{version}` that maps a Gradle plugin id to the implementation coordinate; resolved and cached like any other artifact.
- **Plugin implementation artifact**: The actual library (and its POM) that the marker points to, which provides the plugin's behavior.
- **Default aggregate group**: The existing read-only group repository that fronts multiple upstreams behind one URL; gains the plugin-portal proxy as an additional member.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: With only default configuration and an empty cache, a real Gradle build that applies a published community plugin via the standard plugin block — pointed solely at Relikquary — resolves and applies the plugin successfully (no hand-added upstream repository).
- **SC-002**: After a plugin has been resolved once, repeating the resolution with the upstream made unreachable still succeeds entirely from cache.
- **SC-003**: Plugin artifacts served through the proxy are byte-identical to the same artifacts fetched directly from the portal (verified by checksum), so no client-side integrity check fails.
- **SC-004**: Overriding the plugin-portal upstream URL via environment variable changes where plugin requests are sent, and unsetting it restores the documented default — confirmed without code changes.
- **SC-005**: All existing repository behavior (publish/resolve/proxy/group/auth) and existing tests continue to pass unchanged after the feature is added.
- **SC-006**: A request for a nonexistent plugin coordinate returns a "not found" result and caches nothing, while a request for an uncached artifact during an upstream outage returns a distinct upstream-error result.

## Assumptions

- The Gradle Plugin Portal is reachable by clients as a standard Maven-layout repository at `https://plugins.gradle.org/m2/`; this is the safe default upstream URL (the portal's documented Maven endpoint).
- The new proxy is added to the **existing** default `public` group (resolved order `[releases, maven-central, gradle-plugins]`, see Clarifications) rather than a new group, so locally published and Central-hosted artifacts win on conflict and the portal is consulted last. The proxy remains addressable on its own at `/gradle-plugins` like any named repository.
- Client-side configuration (pointing a Gradle build's plugin resolution at Relikquary) is the operator/developer's responsibility and is unchanged by this feature; the feature only makes the server able to satisfy those requests by default. Documentation will show the client snippet.
- No new repository **kind** is introduced; the plugin portal is just another `proxy` upstream reusing the feature-006 proxy/group machinery, so no new storage capabilities or protocol changes are required.
- Both storage backends (filesystem and S3) cache plugin artifacts identically, inheriting existing proxy behavior; no backend-specific work is expected.
- The round-trip verification picks a small, stable, widely-available published plugin and is gated to auto-skip when offline, exactly as the existing Maven Central proxy round-trip verification is gated.
- Plugin artifacts on the portal are immutable per version (cache-permanently); only metadata is treated as changeable (pass-through), matching existing proxy assumptions.
