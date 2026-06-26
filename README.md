# Relikqary

Relikqary is a self-hosted **artifact repository server** written in Kotlin and Spring Boot. It
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

## Build & run

Requires JDK 21 (provisioned by the Gradle toolchain).

```bash
./gradlew build        # compile + detekt + tests (incl. a real publish/resolve round-trip) + coverage
```

### Run locally (auth disabled — no login)

```bash
./gradlew :backend:bootRun --args='--spring.profiles.active=local'
```

The `local` profile disables authentication and stores artifacts under `./relikqary-store-local`, so
a local Gradle `maven-publish` needs no credentials.

### Run with authentication (publishing requires credentials)

By default, authentication is **on**: resolving stays open, but publishing requires a configured user
with the `PUBLISH` role. Configure a publisher and run:

```bash
./gradlew :backend:bootRun --args='\
  --relikqary.storage.filesystem.root=/tmp/relikqary-store \
  --relikqary.security.users[0].username=ci \
  --relikqary.security.users[0].password={bcrypt}$2a$10$your-bcrypt-hash \
  --relikqary.security.users[0].roles[0]=PUBLISH'
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

Relikqary listens on `http://localhost:8080`. Resolving (Maven or Gradle) needs no credentials.

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `relikqary.storage.filesystem.root` | `./relikqary-store` | Directory where artifacts are persisted |
| `relikqary.publish.release-policy` | `reject` | `reject` or `overwrite` for re-publishing an existing release |
| `relikqary.security.enabled` | `true` | Set `false` to disable auth (open publishing) — used by the `local` profile |
| `relikqary.security.users` | _(empty)_ | Publishers: `{username, password ({bcrypt}…/{noop}…), roles}` |
