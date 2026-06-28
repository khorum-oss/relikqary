# Research: Proxy (Remote) Repositories & Repository Groups

Phase 0 decisions for feature 006. All spec clarifications were resolved in the spec's
`## Clarifications` (2026-06-28); this records the technical choices that follow from them.

## D1 — Upstream HTTP client

**Decision**: Use the JDK built-in `java.net.http.HttpClient` (with `ProxySelector.getDefault()`)
for proxy→upstream fetches; no new production dependency.

**Rationale**:
- **Zero new dependencies** → `gradle/verification-metadata.xml` is untouched, honoring Principle IV
  (supply-chain integrity) with no SHA regeneration and no new trust surface.
- Already proven in this repo (every integration test drives `HttpClient`).
- Streams response bodies (`BodyHandlers.ofInputStream`), so artifact bytes flow straight into storage
  without buffering whole files in memory.
- Honors JVM proxy settings via `ProxySelector.getDefault()` — required here, where outbound HTTPS goes
  through the agent proxy, and useful for operators behind corporate proxies.

**Alternatives considered**:
- *Spring `RestClient`* (in `spring-web`, no new dep either) — viable, but JDK `HttpClient` is already
  the house pattern and keeps streaming explicit.
- *Spring WebFlux `WebClient`* — rejected: pulls in `spring-boot-starter-webflux` (new dependency +
  reactive stack) for no benefit in a blocking servlet app.

## D2 — Configuration model for the new repo kinds

**Decision**: Add a `kind` field to the existing `RepositoryProperties.Repo` (default `HOSTED`, so
current config is unchanged), plus optional fields used per kind. Introduce a `RepositoryKind` enum
`{HOSTED, PROXY, GROUP}`. Keep the existing `type` (`RepositoryType` RELEASE/SNAPSHOT/MIXED) meaningful
only for `HOSTED`.

```
Repo(
  name: String,
  kind: RepositoryKind = HOSTED,
  type: RepositoryType = MIXED,        // HOSTED only
  remoteUrl: String? = null,           // PROXY: upstream base URL
  remoteUsername: String? = null,      // PROXY: optional upstream auth
  remotePassword: String? = null,      // PROXY: optional upstream auth (env/file, never committed)
  members: List<String> = emptyList(), // GROUP: ordered member repo names
)
```

**Rationale**: Additive and backward-compatible — `releases`/`snapshots` keep working with just
`{name, type}`. A flat data class binds cleanly via Spring `@ConfigurationProperties` (sealed/polymorphic
binding is awkward and would churn the existing shape). Separating `kind` from hosted `type` avoids
overloading one enum with two orthogonal concerns.

**Alternatives considered**: overloading `RepositoryType` with `PROXY`/`GROUP` values — rejected:
conflates "what kind of repo" with "hosted acceptance policy" and complicates `RepublishPolicy`.

## D3 — Startup validation of repository config

**Decision**: `RepositoryRegistry` validates the full set at construction (fail-fast on a misconfigured
context):
- `PROXY` MUST have a non-blank `remoteUrl`.
- `GROUP` MUST have ≥1 member; every member MUST name a configured repo; a group MUST NOT list itself;
  members MUST be `HOSTED` or `PROXY` (no nested groups).
Violations throw on bean creation (context fails to start) — same posture as other Spring config errors.

**Rationale**: FR-009. Catching misconfiguration at startup beats surfacing it as confusing 404/500s at
request time.

## D4 — Resolution architecture

**Decision**: Extract read-resolution out of `RepositoryController` into a `RepositoryResolver`
(`@Component`). The controller parses `{repo}/{path}` and delegates GET to the resolver, which dispatches
on `kind`:
- **HOSTED** → `storage.openRead("{repo}/{path}")`.
- **PROXY** → cache-first: serve `storage.openRead(cacheKey)` if present; else fetch upstream via the
  `UpstreamClient`. For an immutable artifact, `storage.write(cacheKey, body)` then re-read and serve.
  For `maven-metadata.xml`, stream the upstream response straight through (pass-through, never cached).
- **GROUP** → try each member in order via the resolver recursively; first `Hit` wins.

The resolver returns a sealed `Resolution`:
`Hit(StoredArtifact)` | `Miss` | `UpstreamError`. The controller maps `Hit`→200, `Miss`→404,
`UpstreamError`→502.

**Rationale**: Keeps the controller thin and pushes the branching into one testable unit. Group recursion
naturally caches under the *member* proxy's name (the member resolves itself), so a group never owns
cache bytes.

**Group + upstream errors**: while iterating members, a `Miss` continues to the next member; a `Hit`
returns immediately; an `UpstreamError` is remembered and iteration continues. After all members: a
remembered `UpstreamError` → `502` (a transient outage in one member isn't reported as a flat 404),
otherwise `404`.

## D5 — Write-once caching & concurrency

**Decision**: Cache by reusing `ArtifactStorage.write(cacheKey, stream)` (atomic temp→move on
filesystem; idempotent `putObject` on S3), then `openRead` to serve. Two clients racing on the same
uncached artifact may both fetch and both write; the bytes are identical and the final store is atomic
(last-write-wins). No locking in this feature.

**Rationale**: Reuses the faithful-storage write path (Principle IV) unchanged. A double-fetch under a
cold-cache race is harmless and not worth a distributed lock for the MVP. (Documented as an accepted
trade-off.)

## D6 — Publish/auth interaction for read-only kinds

**Decision**: A `PUT` to a `PROXY` or `GROUP` repo returns `405 Method Not Allowed` (with `Allow: GET,
HEAD`) when the request reaches the controller. The existing security rule (`PUT /**` requires the
`PUBLISH` role) is unchanged, so when security is enabled an *unauthenticated* PUT still gets `401`
first; an authenticated publisher PUT to a proxy/group gets `405`. Acceptance for the 405 case is tested
with security disabled or with an authenticated publisher.

**Rationale**: 405 is the correct semantic ("this resource never accepts publish"), but auth is
orthogonal and rightly takes precedence — consistent with how 401/403 already precede every handler.

## D7 — Test strategy (per clarification)

**Decision**: Two layers, mirroring the s3mock(local) + MinIO(guarded) split from feature 003:
- **Default / offline**: a deterministic **local stub upstream** built on the JDK
  `com.sun.net.httpserver.HttpServer` (no dependency) that serves a canned artifact (jar + pom + `.sha1`
  + `maven-metadata.xml`). Real Maven/Gradle clients resolve *through the proxy* against this stub — a
  real client + real HTTP boundary, fully offline and CI-safe.
- **Guarded real upstream**: a test that points a proxy at real Maven Central
  (`https://repo1.maven.org/maven2`), guarded to **auto-skip when offline** (a quick reachability probe
  → JUnit `Assumptions.assumeTrue`), analogous to `@Testcontainers(disabledWithoutDocker = true)`.

**Rationale**: Honors Principle II (real round-trips) deterministically while still exercising a genuine
external upstream when the network allows. The stub upstream is a real in-process HTTP server, not a
mock of the client boundary.

## D8 — Default repositories shipped

**Decision**: Ship two additional defaults in `application.yml` alongside `releases`/`snapshots`:
a `maven-central` **proxy** (`remoteUrl: https://repo1.maven.org/maven2`) and a `public` **group**
(`members: [releases, maven-central]`).

**Rationale**: Makes the manager immediately useful and the feature demonstrable end-to-end (one URL for
first-party + Central), consistent with feature 004 shipping useful `releases`/`snapshots` defaults.
Outbound calls happen only when a client actually resolves through the proxy. Operators can remove or
retarget them in config.

## D9 — Browse/manage (UI/API) impact

**Decision**: Minimal, additive. `GET /api/repositories` includes each repo's `kind` (and, for proxy,
that it is a cache). A proxy repo's cached contents are already browsable through the existing
storage-backed listing (no new code). Group aggregation in the browse UI is out of scope: a group has no
backing storage prefix, so `GET /api/repositories/{group}/contents/**` returns an empty listing. No
frontend code changes are required for the feature to function.

**Rationale**: FR-011 (existing browse UI keeps working) with the least surface area; richer group
browsing is explicitly deferred in the spec.
