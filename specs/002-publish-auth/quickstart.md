# Quickstart & Validation: Publish Authentication

## Local development (auth off)

```bash
./gradlew :backend:bootRun --args='--spring.profiles.active=local'
```

Auth is disabled; publish from a local Gradle build with no credentials (validates FR-007/SC-003).

## Authenticated run

```bash
./gradlew :backend:bootRun --args='\
  --relikqary.storage.filesystem.root=/tmp/relikqary-store \
  --relikqary.security.users[0].username=ci \
  --relikqary.security.users[0].password={bcrypt}<hash> \
  --relikqary.security.users[0].roles[0]=PUBLISH'
```

- Publish **with** credentials (`credentials { username; password }` in the Gradle repo block) → succeeds (SC-001).
- Publish **without** credentials → `401` (SC-001 negative).
- Resolve with Maven or Gradle, **no credentials** → succeeds (open read, SC-002).

## Automated validation

```bash
./gradlew build   # AuthPublishTest, AuthDisabledTest, SecurityPropertiesTest, and the
                  # credentialed PublishResolveRoundTripTest all run under the gate
```

The round-trip test publishes with credentials and resolves anonymously via real Maven + Gradle;
`AuthPublishTest` covers 401/403/201 and open read; `AuthDisabledTest` covers the local opt-out.
