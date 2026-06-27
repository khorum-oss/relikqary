# Contract: Maven-Compatible Repository HTTP Protocol

The public contract Relikquary exposes in this MVP. It MUST be what unmodified Maven and Gradle clients
already speak — no custom client. All paths are under a single implicit repository root.

## Path scheme

```
/{groupId with '.' → '/'}/{artifactId}/{version}/{filename}
/{groupId with '.' → '/'}/{artifactId}/maven-metadata.xml
```

Example for `com.example:widget:1.0.0`:

```
/com/example/widget/1.0.0/widget-1.0.0.pom
/com/example/widget/1.0.0/widget-1.0.0.jar
/com/example/widget/1.0.0/widget-1.0.0.jar.sha1
/com/example/widget/1.0.0/widget-1.0.0-sources.jar
/com/example/widget/maven-metadata.xml
```

## Operations

### PUT `/{path}` — publish a file

- Request body: raw file bytes (artifact, POM, checksum sidecar, signature, or `maven-metadata.xml`).
- Behavior: store bytes byte-for-byte at the key equal to `{path}`, subject to `RepublishPolicy`.
- Responses:
  - `201 Created` (or `200 OK`) — stored successfully (new key, or allowed overwrite).
  - `409 Conflict` — key exists for a RELEASE coordinate under default `reject` policy; stored bytes
    are left unchanged.
  - `400 Bad Request` — path is not a valid repository-layout path.

### GET `/{path}` — resolve/download a file

- Behavior: stream back the stored bytes for key `{path}`, byte-for-byte identical to what was PUT.
- Responses:
  - `200 OK` — body is the stored file (streamed; correct length).
  - `404 Not Found` — no such key. MUST be a clean 404 so clients treat the artifact as absent and
    fall through to other configured repositories (FR-008); MUST NOT be a 5xx.

### HEAD `/{path}` — existence/metadata check

- Behavior: same resolution as GET but no body; clients use this to probe availability.
- Responses: `200 OK` (exists) or `404 Not Found` (absent).

## Invariants

- **Faithful bytes**: bytes returned by GET equal bytes received by the corresponding PUT, always
  (FR-003, SC-004).
- **Sidecars preserved**: checksum (`.sha1/.md5/.sha256/.sha512`) and signature (`.asc`) files are
  stored and served unaltered so client-side verification passes (FR-006).
- **Release immutability (default)**: once a RELEASE file key exists, a subsequent PUT does not change
  it (409) under default policy (FR-010, SC-007).
- **Not-found is non-fatal**: a missing key yields 404, never an error that aborts client resolution
  (FR-008, SC-006).
- **Path safety**: a request path that attempts to escape the repository root (`..` segments,
  absolute escapes, empty segments) yields `400 Bad Request` and never resolves outside the configured
  storage root (FR-012, SC-008).

## Out of scope for this contract (deferred)

- Authentication/authorization headers (open access in this slice).
- Server-side `maven-metadata.xml` synthesis/merge (client-uploaded metadata is stored/served as-is).
- Gradle Module Metadata (`.module`) feature-variant semantics (files pass through as bytes only).
- Multiple named repositories / repository-management endpoints.
