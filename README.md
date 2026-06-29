# Relikquary

Relikquary is a self-hosted **artifact repository server** written in Kotlin and Spring Boot. It
ingests artifact publishes from Gradle (the standard `maven-publish` plugin) and serves them back
over HTTP in a Maven-compatible repository layout, so any standard Maven or Gradle client pointed at
the right endpoint can resolve and download them. Final storage is configurable, so artifacts can be
persisted wherever you point it.

## Status

First MVP — the core **publish-and-resolve** vertical slice:

- Accept a Gradle `maven-publish` upload and store every file byte-for-byte.
- Serve artifacts back in Maven layout for unmodified **Maven and Gradle** clients.
- Configurable filesystem storage location (no code change to repoint).
- Re-publish policy: release coordinates are immutable by default (configurable), SNAPSHOTs are
  overwritable.

See the full specification, plan, and tasks under
[`specs/001-publish-resolve-mvp/`](specs/001-publish-resolve-mvp/), and the runnable validation
guide in [`specs/001-publish-resolve-mvp/quickstart.md`](specs/001-publish-resolve-mvp/quickstart.md).
Project principles live in [`.specify/memory/constitution.md`](.specify/memory/constitution.md).

## Web UI

A SvelteKit web UI (in [`frontend/`](frontend/)) browses repositories, drills into artifacts and
versions, shows file details (size, checksums, last-modified), downloads files, and deletes
versions/files. It talks to the backend's JSON browse/manage API under `/api` (separate from the
Maven protocol); reads are open, deletes require the `PUBLISH` role when auth is enabled.

```bash
# Run standalone against a running backend (vite proxies /api + downloads to :8080):
cd frontend && npm install && npm run dev          # http://localhost:5173
# End-to-end browser test (starts a seeded backend + runs Playwright):
cd frontend && bash scripts/e2e.sh
```

The UI is a separate, independently deployable module. To **bundle** it into the backend jar (served
at `/ui`, same origin), build with `-PbundleFrontend`:

```bash
./gradlew :backend:bootJar -PbundleFrontend        # serves the UI at http://localhost:8080/ui/
```

## Build & run

Requires JDK 21 (provisioned by the Gradle toolchain). Node 22 is needed only for the frontend.

```bash
./gradlew build        # backend: compile + detekt + tests (incl. a real publish/resolve round-trip) + coverage
```

### Run locally (auth disabled — no login)

```bash
./gradlew :backend:bootRun --args='--spring.profiles.active=local'
```

The `local` profile disables authentication and stores artifacts under `./relikquary-store-local`, so
a local Gradle `maven-publish` needs no credentials.

### Run with authentication (publishing requires credentials)

By default, authentication is **on**: resolving stays open, but publishing requires a configured user
with the `PUBLISH` role. Configure a publisher and run:

```bash
./gradlew :backend:bootRun --args='\
  --relikquary.storage.filesystem.root=/tmp/relikquary-store \
  --relikquary.security.users[0].username=ci \
  --relikquary.security.users[0].password={bcrypt}$2a$10$your-bcrypt-hash \
  --relikquary.security.users[0].roles[0]=PUBLISH'
```

Then publish from Gradle with credentials:

```kotlin
publishing {
    repositories {
        maven {
            url = uri("http://localhost:8080")
            credentials { username = "ci"; password = "..." }
        }
    }
}
```

Relikquary listens on `http://localhost:8080`. Resolving (Maven or Gradle) needs no credentials.

### Per-repository authorization

By default reads are open and writes need the global `PUBLISH` role. Any repository can additionally
declare an `access` block granting **read**, **publish**, and **delete** to specific principals — a
username, or a role written `@role`:

```yaml
relikquary:
  repositories:
    - name: releases            # no access block ⇒ open reads, PUBLISH-gated writes (unchanged)
      type: release
    - name: private-libs        # a private repository
      type: mixed
      access:
        read:    [alice, "@platform"]
        publish: [alice]
        delete:  [alice]
```

- An action with **no list** keeps its default: read → open; publish/delete → global `PUBLISH` role.
  An explicit list **overrides** the default for that action (a listed user is allowed even without the
  global `PUBLISH` role; an unlisted `PUBLISH` holder is refused).
- Denied requests return `401` (with a Basic challenge) when unauthenticated, or `403` when
  authenticated without permission. An unknown repository is still `404` (existence is not secret).
- Read authorization applies to both the Maven path and the browse API, over every file in the repo
  (artifacts, `maven-metadata.xml`, checksums).
- For a **group**, a read request is served by the first member that both has the artifact and permits
  the user; a member that denies the user is skipped (so a private member never masks a public copy).
- With `relikquary.security.enabled=false`, all per-repository rules are ignored — everything is open.

### Named repositories

Relikquary serves **named, typed repositories** addressed by a path prefix — there is no implicit repo
at the root. The defaults are `releases` (immutable) and `snapshots` (overwritable); point clients at
`http://localhost:8080/releases` (or `/snapshots`). A repository's type governs what it accepts:

| Type | Accepts | Existing target |
|------|---------|-----------------|
| `release` | release versions only (`-SNAPSHOT` → 400) | immutable (409, unless overwrite configured) |
| `snapshot` | `-SNAPSHOT` only (release → 400) | overwritten |
| `mixed` | both | release immutable, snapshot/metadata overwritten |

Define repositories under `relikquary.repositories` (`{name, type}`); an unknown repo name returns 404.

### Gradle modules

Relikquary is a first-class Gradle repository. When you publish from Gradle with Gradle Module Metadata
(the `.module` file Gradle emits alongside the POM and jar, carrying variants, capabilities, and
dependency constraints), Relikquary stores and serves it byte-for-byte — recognized as a distinct
artifact kind, immutable for a release version and overwritable for a snapshot, and **cached** by a proxy
like the jar/POM (a versioned `.module` is not volatile, unlike `maven-metadata.xml`). A consuming Gradle
build resolves through Relikquary using the module metadata, so variant/capability selection works
end-to-end — including through a proxy. Maven clients that ignore `.module` resolve exactly as before.

In the web UI, a coordinate that has a `.module` is badged as a **Gradle module**. Every coordinate
offers copy-paste **consume snippets** for Gradle (Kotlin DSL and Groovy DSL) and Maven (pointing at this
repository's URL), and a Gradle module's detail view lists each variant with its attributes, capabilities,
dependencies, and files — parsed from the module metadata (malformed metadata degrades gracefully). The
parsed metadata is also available over the browse API at
`GET /api/repositories/{repo}/module/{group}/{artifact}/{version}/{artifact}-{version}.module`.

### Proxy & group repositories

Beyond locally **hosted** repos, a repository's `kind` can make it a **proxy** or a **group** (both
read-only — publishing returns 405):

- **`proxy`** transparently fetches from an upstream (`remoteUrl`) on a cache miss, stores the bytes
  byte-for-byte, and serves later requests from the local cache without contacting the upstream.
  `maven-metadata.xml` is always served fresh from the upstream (never cached), so newly published
  upstream versions stay resolvable. Optional `remoteUsername`/`remotePassword` (for authenticated
  upstreams) come from the environment — never commit them.
- **`group`** aggregates ordered `members` (hosted or proxy) behind one URL and returns the first
  member that has the artifact.

The defaults ship a `maven-central` proxy (→ `https://repo1.maven.org/maven2`), a `gradle-plugins`
proxy for the **Gradle Plugin Portal** (→ `https://plugins.gradle.org/m2/`), and a `public` group over
`[releases, maven-central, gradle-plugins]`, so a build can point at a single URL for first-party
artifacts, proxied Central dependencies, **and Gradle plugins**:

```yaml
relikquary:
  repositories:
    - name: releases
      type: release
    - name: maven-central
      kind: proxy
      remoteUrl: https://repo1.maven.org/maven2
    - name: gradle-plugins
      kind: proxy
      remoteUrl: https://plugins.gradle.org/m2/   # override with RELIKQUARY_GRADLE_PLUGIN_PORTAL_URL
    - name: public
      kind: group
      members: [releases, maven-central, gradle-plugins]   # portal consulted last
```

A consuming Gradle build resolves both its dependencies and its plugins through the one `public` URL.
Plugin resolution is wired in `settings.gradle.kts` (evaluated before build scripts); declaring
`pluginManagement.repositories` explicitly replaces the default `gradlePluginPortal()`, so plugins
flow through Relikquary:

```kotlin
// settings.gradle.kts on a consuming build
pluginManagement {
    repositories { maven { url = uri("http://localhost:8080/public") } }
}
dependencyResolutionManagement {
    repositories { maven { url = uri("http://localhost:8080/public") } }
}
```

The `gradle-plugins` proxy is also addressable on its own at `/gradle-plugins`. Plugin marker POMs
(`{id}:{id}.gradle.plugin`) and implementation artifacts are cached byte-for-byte like any proxied
artifact, so a plugin resolved once keeps working even when the portal is unreachable.

A proxy or group request for something present nowhere returns 404; an upstream outage on a cache miss
returns 502.

### Retention & cleanup

Reclaim storage with opt-in per-repository policies (off until configured). Snapshot repos keep the
newest builds; proxy caches stay bounded; releases and `maven-metadata.xml` are never touched.

```yaml
relikquary:
  cleanup:
    enabled: true            # run cleanup on a schedule (default false)
    interval: PT1H
  repositories:
    - name: snapshots
      type: snapshot
      retention:
        snapshot:
          keepLast: 5        # keep the 5 newest builds per artifact
          maxAge: P30D       # and/or purge builds older than 30 days
    - name: maven-central
      kind: proxy
      remoteUrl: https://repo1.maven.org/maven2
      retention:
        cache:
          maxAge: P14D       # evict cached artifacts older than 14 days
          maxSize: 5GB       # and/or keep the cache within a size budget
```

Trigger a run on demand (requires the `PUBLISH` role when auth is enabled), or preview with a dry-run:

```bash
curl -u ci:secret -X POST 'http://localhost:8080/api/cleanup?dryRun=true'   # report only
curl -u ci:secret -X POST  http://localhost:8080/api/cleanup                # run + report
```

Evicted proxy artifacts re-fetch from the upstream on the next request, so cache eviction is safe.

### Object storage (S3 / DigitalOcean Spaces)

Set `relikquary.storage.backend=s3` and point it at any S3-compatible endpoint (AWS S3, DigitalOcean
Spaces, MinIO). Credentials come from the environment — never commit them.

```bash
RELIKQUARY_S3_ENDPOINT=https://nyc3.digitaloceanspaces.com \
RELIKQUARY_S3_BUCKET=my-artifacts \
RELIKQUARY_S3_ACCESS_KEY=... RELIKQUARY_S3_SECRET_KEY=... \
./gradlew :backend:bootRun --args='--relikquary.storage.backend=s3'
```

### Observability & operational readiness

Relikquary exposes liveness/readiness probes, Prometheus metrics, and an opt-in structured request log
under `/actuator` — separate from the Maven (`/{repo}/…`) and browse (`/api`) surfaces.

**Probes** are public (orchestrators can't authenticate). Liveness reflects process health; readiness
also reflects whether the configured storage backend is reachable/writable, so a storage-detached
instance is pulled from rotation without being restarted:

```bash
curl http://localhost:8080/actuator/health/liveness    # {"status":"UP"}
curl http://localhost:8080/actuator/health/readiness   # 503 when storage is unreachable
```

**Metrics** are scraped in Prometheus format and cover HTTP request rate/latency/error
(`http_server_requests_*`), publish/resolve counts, proxy cache hit/miss and upstream outcomes, cleanup
items-removed/bytes-reclaimed, and storage usage:

```bash
curl -u ci:secret http://localhost:8080/actuator/prometheus | grep relikquary_
```

A proxy upstream being unreachable shows as `DEGRADED` in the detailed health view (`/actuator/health`,
HTTP 200) **without** failing liveness or readiness.

**Structured request logging** is opt-in (off by default). When enabled, each request emits one JSON line
on the `relikquary.access` logger with method, repository, path, status, bytes, duration, and the
authenticated principal (omitted when anonymous):

```yaml
relikquary:
  observability:
    request-log:
      enabled: true
```

**Authorization**: only the liveness and readiness probes are public. Every other operational endpoint
(detailed health, metrics, info, …) requires the global `PUBLISH` role when security is enabled (`401`
unauthenticated, `403` authenticated-without-role); when security is disabled they are all open. Health
and metrics output never contains storage/upstream credentials or passwords.

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `relikquary.repositories[].name` | _(none)_ | Repository name = path prefix (`/{name}/…`) |
| `relikquary.repositories[].kind` | `hosted` | `hosted`, `proxy`, or `group` |
| `relikquary.repositories[].type` | `mixed` | Hosted acceptance/mutability: `release`, `snapshot`, or `mixed` |
| `relikquary.repositories[].remoteUrl` | _(none)_ | Proxy upstream base URL (required for `proxy`) |
| `relikquary.repositories[].remoteUsername` / `remotePassword` | _(none)_ | Optional upstream credentials (supply via env) |
| `relikquary.repositories[].members` | _(empty)_ | Ordered member repo names (required for `group`) |
| `relikquary.storage.backend` | `filesystem` | `filesystem` or `s3` (S3-compatible object storage) |
| `relikquary.storage.filesystem.root` | `./relikquary-store` | Directory where artifacts are persisted (filesystem backend) |
| `relikquary.storage.s3.endpoint` | _(none)_ | S3 endpoint override (e.g. DigitalOcean Spaces / MinIO) |
| `relikquary.storage.s3.region` | `us-east-1` | S3 region |
| `relikquary.storage.s3.bucket` | _(none)_ | Target bucket (must already exist) |
| `relikquary.storage.s3.access-key` / `secret-key` | _(none)_ | S3 credentials (supply via env) |
| `relikquary.storage.s3.path-style-access` | `true` | Path-style addressing (needed by MinIO/Spaces) |
| `relikquary.publish.release-policy` | `reject` | `reject` or `overwrite` for re-publishing an existing release |
| `relikquary.security.enabled` | `true` | Set `false` to disable auth (open publishing) — used by the `local` profile |
| `relikquary.security.users` | _(empty)_ | Publishers: `{username, password ({bcrypt}…/{noop}…), roles}` |
| `relikquary.cleanup.enabled` | `false` | Run retention/cleanup on a schedule |
| `relikquary.cleanup.interval` | `PT1H` | Schedule interval (ISO-8601 duration) |
| `relikquary.observability.request-log.enabled` | `false` | Emit one structured (JSON) log line per request |
| `relikquary.observability.storage-probe-ttl` | `PT2S` | TTL cache for the storage readiness probe |
| `relikquary.observability.upstream-health-ttl` | `PT30S` | TTL cache for proxy-upstream reachability checks |
| `relikquary.observability.storage-usage-refresh` | `PT5M` | Refresh interval for the storage-usage gauges |
| `management.endpoints.web.exposure.include` | `health,info,prometheus,metrics` | Exposed operational endpoints |
