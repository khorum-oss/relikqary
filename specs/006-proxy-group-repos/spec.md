# Feature Specification: Proxy (Remote) Repositories & Repository Groups

**Feature Branch**: `006-proxy-group-repos`

**Created**: 2026-06-28

**Status**: Draft

**Input**: User description: "Add PROXY repositories that transparently fetch artifacts from a
configured upstream remote (e.g. Maven Central) on a cache miss, store the bytes locally as a cache,
and serve subsequent requests from that cache; and GROUP repositories that aggregate several member
repositories (hosted or proxy) behind one URL, resolving by first match. Proxy and group repos are
read-only (no publishing). Keep existing hosted repos, auth, storage, and browse UI working. Remote
and group definitions live in the same config as named repositories."

## Clarifications

### Session 2026-06-28

- Q: Proxy metadata freshness vs cached files? → A: Pass-through — `maven-metadata.xml` is always
  fetched fresh from the upstream on each request; only immutable artifact files are cached
  (permanently). No TTL/clock.
- Q: Group `maven-metadata.xml` when multiple members match? → A: First-match (same rule as artifact
  files); merging version listings across members is out of scope.
- Q: Proxy round-trip test upstream? → A: Both — a deterministic local stub upstream by default
  (offline, CI-safe, real client round-trip) plus a real-Maven-Central test guarded to auto-skip when
  offline (mirrors the s3mock + MinIO precedent in spec 003).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Resolve third-party dependencies through a proxy repository (Priority: P1)

A developer points a build at a configured proxy repo (e.g. `/maven-central`) whose upstream is Maven
Central. When the build requests a dependency that isn't cached yet, Relikquary fetches it from the
upstream, stores the exact bytes locally, and serves it. Subsequent requests for the same artifact are
served from the local cache without contacting the upstream.

**Why this priority**: Proxying upstream repos is the core of acting as a real repository manager —
one trusted, cacheable entry point for external dependencies.

**Independent Test**: Resolve a known artifact through the proxy on a cold cache (fetched from
upstream, byte-for-byte), then resolve it again with the upstream unavailable and confirm it still
serves from cache.

**Acceptance Scenarios**:

1. **Given** a proxy repo with an upstream, **When** a client requests an artifact not yet cached,
   **Then** Relikquary fetches it from the upstream, stores it, and returns it with the upstream bytes
   unchanged.
2. **Given** an artifact already cached by the proxy, **When** a client requests it again, **Then** it
   is served from the local cache without contacting the upstream.
3. **Given** an artifact that does not exist upstream, **When** a client requests it, **Then** the
   response is `404` (and nothing is cached).
4. **Given** the upstream is unreachable on a cache miss, **When** a client requests an uncached
   artifact, **Then** the response is a gateway error (`502`), not a `404`.

---

### User Story 2 - Resolve everything through a single group URL (Priority: P1)

A developer points a build at one group repo (e.g. `/public`) that aggregates a hosted repo
(first-party releases) and a proxy repo (Maven Central). The build resolves both its in-house
artifacts and its external dependencies through that one URL; the group checks each member in
configured order and returns the first match.

**Why this priority**: A single aggregated URL is what makes the manager convenient — consumers
configure one repository instead of several.

**Independent Test**: Configure a group over a hosted member and a proxy member; resolve a first-party
artifact (served by the hosted member) and a third-party dependency (served via the proxy member)
through the group URL.

**Acceptance Scenarios**:

1. **Given** a group whose first member is a hosted repo holding the artifact, **When** a client
   requests it, **Then** the group returns the hosted member's bytes.
2. **Given** a group where only a later proxy member can satisfy the request, **When** a client
   requests it, **Then** the group returns the artifact resolved (and cached) via that proxy member.
3. **Given** an artifact present in no member, **When** a client requests it, **Then** the group
   returns `404`.

---

### User Story 3 - Proxy/group repos are read-only; existing behavior preserved (Priority: P2)

Proxy and group repos resolve/serve only — they cannot be published to. Hosted release/snapshot/mixed
repos, authentication, storage backends, and the browse/manage UI keep working exactly as before.

**Why this priority**: Adding new repo kinds must not weaken the publish rules or regress any existing
capability.

**Independent Test**: A `PUT` to a proxy or group repo is rejected; an existing hosted publish/resolve
round-trip and the DELETE auth matrix still pass unchanged.

**Acceptance Scenarios**:

1. **Given** a proxy repo, **When** a client attempts to publish (`PUT`) to it, **Then** the request is
   rejected (`405`) and nothing is stored.
2. **Given** a group repo, **When** a client attempts to publish (`PUT`) to it, **Then** the request is
   rejected (`405`).
3. **Given** the existing hosted repos, **When** publish/resolve and DELETE run as before, **Then**
   behavior (acceptance, immutability, auth) is unchanged.

### Edge Cases

- **Unknown repo name**: first path segment is not a configured repo → `404` (unchanged).
- **Upstream 404 vs upstream error**: not-found upstream → `404` (no cache write); unreachable/5xx
  upstream → `502` so a transient outage isn't cached as a miss.
- **Metadata freshness**: `maven-metadata.xml` from a proxy reflects the upstream (not served stale
  from a permanent cache), so newly published upstream versions become resolvable.
- **Group ordering**: members are tried in configured order; the first member that has the artifact
  wins, even if a later member also has it.
- **Misconfiguration**: a group member naming a repo that isn't configured, a proxy without an upstream
  URL, or a group listing itself → rejected as invalid configuration at startup.
- **Read auth**: reads through proxy/group are open (consistent with existing read-open policy);
  upstream credentials, if any, are used only for the proxy→upstream fetch and never exposed to
  clients.
- **Path traversal** in the repo segment or artifact path → `400` (preserved).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Relikquary MUST support a `proxy` repository kind, defined in the same configuration as
  named repositories, with an upstream remote location and optional upstream credentials; no code
  change to add one.
- **FR-002**: On a request to a proxy repo, Relikquary MUST serve the artifact from its local cache if
  present; otherwise fetch it from the upstream, store the bytes, and serve them.
- **FR-003**: Bytes fetched from an upstream MUST be stored and served byte-for-byte, preserving the
  upstream's checksums and signatures (Principle IV); a cached artifact MUST be served on subsequent
  requests without contacting the upstream.
- **FR-004**: A proxy request for an artifact that does not exist upstream MUST return `404` and cache
  nothing; a proxy request that fails because the upstream is unreachable or errors MUST return a
  gateway error (`502`) and cache nothing.
- **FR-005**: A proxy MUST serve `maven-metadata.xml` by fetching it fresh from the upstream on each
  request (pass-through, not cached), so versions published upstream after first contact remain
  resolvable; only immutable artifact files are cached.
- **FR-006**: Relikquary MUST support a `group` repository kind, defined in configuration as an ordered
  list of member repository names (each member a hosted or proxy repo).
- **FR-007**: A request to a group repo MUST be resolved by trying each member in configured order and
  returning the first member that has (or can fetch) the artifact; if no member can, the group MUST
  return `404`.
- **FR-008**: Publishing (`PUT`) to a proxy or group repo MUST be rejected (`405`); these repo kinds
  are read-only.
- **FR-009**: Configuration MUST be validated at startup: a group member referencing an unconfigured
  repo, a proxy missing its upstream, or a group referencing itself MUST be reported as invalid.
- **FR-010**: A request whose first path segment is not a configured repository MUST return `404`
  (unchanged).
- **FR-011**: Existing hosted release/snapshot/mixed repositories, authentication (publish gated by the
  `PUBLISH` role; read open), the configurable storage backends, and the browse/manage API and UI MUST
  continue to work unchanged.
- **FR-012**: Upstream credentials (when configured) MUST be supplied via configuration/environment and
  MUST NOT be committed, and MUST never be exposed to resolving clients.

### Key Entities

- **Proxy Repository**: a named, read-only repository backed by an upstream remote; caches fetched
  artifacts locally (`name`, `kind = proxy`, upstream location, optional upstream credentials).
- **Group Repository**: a named, read-only repository aggregating an ordered list of member
  repositories (`name`, `kind = group`, ordered `members`).
- **Upstream Remote**: the external Maven-layout repository a proxy fetches from (e.g. Maven Central).
- **Cache**: the locally stored copy of artifacts a proxy has fetched, namespaced under the proxy
  repo's name in the configured storage backend.

## Success Criteria *(mandatory)*

- **SC-001**: A real Gradle/Maven build resolves an external dependency through a proxy repo on a cold
  cache; the artifact is then present locally and a second resolve succeeds with the upstream
  unavailable.
- **SC-002**: Cached proxy bytes are byte-for-byte identical to the upstream's (checksums verify).
- **SC-003**: A build pointed at a single group URL resolves both a first-party artifact (from a hosted
  member) and a third-party dependency (via a proxy member).
- **SC-004**: A `PUT` to a proxy or group repo is rejected (`405`); nothing is stored.
- **SC-005**: A proxy/group request for an artifact present nowhere returns `404`, and an upstream
  outage on a cache miss returns `502`; the existing hosted publish/resolve round-trip still passes.

## Assumptions

- **Cache freshness**: immutable artifact files (jars, poms, etc.) cached by a proxy are kept
  permanently; `maven-metadata.xml` reflects the upstream on each request rather than being served from
  a permanent cache. Time-based revalidation/TTL policies are out of scope for this feature.
- **Group metadata aggregation**: a group resolves by first-match per request; merging
  `maven-metadata.xml` version listings across members into a combined listing is out of scope (concrete
  coordinate resolution works via first-match).
- **Nested groups**: a group's members are hosted or proxy repos; a group as a member of another group
  is out of scope.
- **Storage**: a proxy caches into the same configured storage backend (filesystem or S3) as hosted
  repos, namespaced by the proxy repo name; per-repo storage targets remain out of scope.
- **Upstream auth**: upstreams are anonymous by default (e.g. Maven Central); optional upstream
  credentials are supported via config/env for authenticated upstreams.
- **Read auth**: reads through proxy and group repos are open, consistent with the existing read-open
  policy; per-repo authorization remains a later spec.
- **Browse/manage UI**: a proxy repo browses its locally cached contents, and deleting from a proxy
  evicts cache entries; aggregated browsing of a group is out of scope for this feature.
- **Testing**: proxy round-trips run against a deterministic local stub upstream by default (offline,
  CI-safe, exercising a real Maven/Gradle client through the proxy), with an additional real-Maven-Central
  test guarded to auto-skip when offline — mirroring the s3mock (local) + MinIO (guarded) split in
  spec 003.
