# Phase 1 Data Model: First-Class Gradle Plugin Portal Support

This feature introduces **no schema or class change**. It adds default *instances* of existing
configuration entities and relies on the existing resolution data flow. The "model" here is therefore the
shipped default configuration plus the resolution walkthrough that proves it satisfies the spec.

## Configuration entities (existing, from features 004 & 006)

- **`RepositoryProperties.Repo`** (`config/RepositoryProperties.kt`) — unchanged. Fields used by this
  feature: `name`, `kind` (`PROXY`/`GROUP`), `remoteUrl` (proxy upstream), `members` (group order).
- **`RepositoryKind`** (`repository/RepositoryKind.kt`) — unchanged; `PROXY` and `GROUP` already exist.
- **`RepositoryRegistry`** — unchanged; existing startup validation already accepts a proxy with a
  `remoteUrl` and a group whose members reference defined repositories (a group containing a proxy is
  already valid: `public` already contains the `maven-central` proxy).

## New default instances (shipped in `application.yml`)

| Repo | kind | key fields |
|------|------|-----------|
| `gradle-plugins` | `proxy` | `remoteUrl = ${RELIKQUARY_GRADLE_PLUGIN_PORTAL_URL:https://plugins.gradle.org/m2/}` |
| `public` (modified) | `group` | `members = [releases, maven-central, gradle-plugins]` (was `[releases, maven-central]`) |

Validation rules (all already enforced by `RepositoryRegistry`, no new code):
- `gradle-plugins.remoteUrl` is non-blank → required for a proxy; satisfied by the default.
- every name in `public.members` must be a defined repository → `gradle-plugins` now is.
- no group cycles; `public` references only non-group repos → still true.

## Cache key namespace

Cached plugin artifacts are stored under `gradle-plugins/{maven-path}` via the existing
`{proxyName}/{path}` scheme. This is disjoint from `maven-central/{path}`, so a coordinate present on both
the portal and Central is cached twice under distinct keys — no collision, each byte-faithful to its source.

## Resolution walkthrough (proving the acceptance scenarios)

A Gradle build applying plugin `com.example.foo` version `1.2.3` through `…/public` issues these requests;
each is resolved by `RepositoryResolver` group first-match over `[releases, maven-central, gradle-plugins]`:

1. **Marker POM** `com/example/foo/com.example.foo.gradle.plugin/1.2.3/com.example.foo.gradle.plugin-1.2.3.pom`
   - `releases` (hosted): not present → miss
   - `maven-central` (proxy): upstream 404 → miss (nothing cached)
   - `gradle-plugins` (proxy): cache miss → fetch from portal `/m2/…` → store under `gradle-plugins/…` → **Hit**
   - (subsequent requests: `gradle-plugins` cache **Hit** without upstream contact — US1#3, US2#1)
2. **Implementation POM + jar** at the coordinate the marker maps to — same first-match walk; served by
   whichever member has it (Central if the impl lives there, else the portal), cached under that proxy's key.
3. **Metadata** (`maven-metadata.xml`, if requested): proxy members pass-through to upstream (never cached),
   so newly published plugin versions appear without cache eviction (edge case: changing metadata).

### Status outcomes (existing `Resolution` → HTTP mapping, unchanged)

| Situation | Resolution | HTTP |
|-----------|-----------|------|
| Artifact cached, or fetched fresh from a member | `Hit` | `200` |
| Coordinate not found on any member (incl. portal 404) | `Miss` | `404` |
| Uncached + portal/Central unreachable | `UpstreamError` | `502` |
| `PUT`/upload to `gradle-plugins` or `public` | rejected (read-only) | `405` (`Allow: GET, HEAD`) |

The `404` vs `502` distinction (FR-008, SC-006) is exactly the existing proxy behavior: a definite upstream
404 yields `Miss`/`404` and caches nothing, while a connection failure yields `UpstreamError`/`502`.
