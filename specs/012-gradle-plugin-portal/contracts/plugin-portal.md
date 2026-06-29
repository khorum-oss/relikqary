# Contract: First-Class Gradle Plugin Portal Support

This feature adds no new HTTP endpoint or wire format. The contract surface is (1) the **default
configuration** Relikquary ships, (2) the **environment override**, and (3) the **client configuration**
operators are expected to use. Wire behavior is the existing proxy/group contract (see
`specs/006-proxy-group-repos/contracts/proxy-group.md`).

## 1. Default configuration contract (server)

Shipped in `backend/src/main/resources/application.yml` under `relikquary.repositories`:

```yaml
    - name: gradle-plugins
      kind: proxy
      remoteUrl: ${RELIKQUARY_GRADLE_PLUGIN_PORTAL_URL:https://plugins.gradle.org/m2/}
    - name: public
      kind: group
      members: [releases, maven-central, gradle-plugins]
```

Guarantees:
- A `gradle-plugins` proxy repository exists by default with the portal `/m2/` endpoint as its upstream.
- The default `public` group includes `gradle-plugins` as its **last** member; `releases` and
  `maven-central` retain their existing relative order and behavior.
- `gradle-plugins` is independently addressable at `/{base}/gradle-plugins/…` like any named repository.

## 2. Environment override contract

- `RELIKQUARY_GRADLE_PLUGIN_PORTAL_URL` — when set, replaces the portal upstream base URL; when unset, the
  default `https://plugins.gradle.org/m2/` applies. No code change required to override (FR-007, SC-004).
- No credentials are required or configured for the public portal. (Should a mirror need auth, the existing
  `remoteUsername`/`remotePassword` proxy fields apply, supplied via env — never committed.)

## 3. Wire behavior (inherited, unchanged)

For any path under `/{base}/gradle-plugins/…` or resolved via `/{base}/public/…`:

| Method | Condition | Response |
|--------|-----------|----------|
| `GET`/`HEAD` | cached, or fetched from upstream | `200` with byte-faithful artifact |
| `GET`/`HEAD` | not found on any member / upstream `404` | `404`, nothing cached |
| `GET`/`HEAD` | uncached and upstream unreachable | `502` |
| `GET`/`HEAD` | `maven-metadata.xml` | pass-through, fresh from upstream, never cached |
| `PUT`/`POST`/`DELETE` | any | `405 Method Not Allowed`, `Allow: GET, HEAD` |

Artifacts (marker POMs, implementation jars/POMs, `.sha1`/`.sha256`/`.asc`) are preserved byte-for-byte, so
client-side checksum/signature verification succeeds identically to resolving from the portal directly.

## 4. Client configuration contract (operator/developer, documented)

To resolve plugins through Relikquary, a Gradle build declares the Relikquary group URL for plugin
resolution in `settings.gradle.kts` (evaluated before build scripts):

```kotlin
pluginManagement {
    repositories {
        maven { url = uri("https://<relikquary-host>/public") }
    }
}
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://<relikquary-host>/public") }
    }
}
```

With this, `plugins { id("…") version "…" }` blocks resolve their marker + implementation through
Relikquary. This client wiring is the developer's responsibility; the server change only makes those
requests resolvable by default.

## Backward compatibility

Additive only. Pre-existing repositories (`releases`, `snapshots`, `maven-central`, `public`) keep their
names and behavior; `public` only gains a trailing member. Every coordinate resolvable before this feature
resolves the same way after it (first-match preserves existing member order). No MAJOR version bump.
