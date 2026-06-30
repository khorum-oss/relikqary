# Contract: Server-Side Hosted maven-metadata.xml

No new HTTP endpoint. The contract is **what the server generates and serves** for hosted repositories, and
the guarantee that everything else is unchanged. Wire paths are the existing Maven repository layout.

## 1. What is generated (hosted repos only)

For a hosted coordinate `{repo}/{group}/{artifactId}`:

- **Artifact-level**: `{group-path}/{artifactId}/maven-metadata.xml` (+ `.sha1`, `.md5`) — authoritative
  version listing built from stored versions: `<versions>`, `<latest>`, `<release>` (omitted if none),
  `<lastUpdated>`.
- **Version-level (snapshots)**: `{group-path}/{artifactId}/{version}-SNAPSHOT/maven-metadata.xml`
  (+ `.sha1`, `.md5`) — `<snapshot>` newest timestamp/buildNumber + `<snapshotVersions>` for the newest build.

Element shapes are in `data-model.md`. Output is valid Maven-layout `maven-metadata.xml` that unmodified
Maven and Gradle clients consume.

## 2. Triggers

| Trigger | Behavior |
|---------|----------|
| Successful publish (`201`/`200`) of a hosted coordinate file (not `maven-metadata.xml`) | regenerate artifact-level metadata; if the version is `-SNAPSHOT`, also regenerate that version's snapshot metadata |
| Publish of `maven-metadata.xml` to a hosted repo | accepted and stored (clients unchanged), but superseded — the server's authoritative copy is what subsequent regeneration/serving reflects |
| `GET` of a hosted `maven-metadata.xml` when the stored file is absent | computed on read, served, and written through (hybrid fallback) |

## 3. Guarantees

- **Authoritative listing**: the served artifact-level listing reflects **all** versions in storage,
  independent of any single client's uploaded metadata (FR-001, FR-002).
- **Checksums consistent**: `.sha1`/`.md5` match the exact served metadata bytes (FR-003) — no client
  integrity failure.
- **Snapshots**: clients resolve the newest stored build; snapshot versions appear in the artifact listing
  (FR-004).
- **Convergence**: concurrent/sequential publishes of the same coordinate converge to the full set; no
  half-written metadata is served (FR-007).
- **Faithful storage**: no other artifact (POM/jar/sources/javadoc/checksums/signatures) is modified (FR-008).

## 4. Explicitly unchanged

- **Proxy/remote repositories**: metadata is **never** generated or overridden — pass-through preserved
  (FR-006, SC-005).
- **Group repositories**: member resolution unchanged.
- **Publish/resolve/auth/cleanup**: behavior unchanged except hosted metadata now being server-authoritative
  (FR-010). The re-publish policy still treats `maven-metadata.xml` as always-overwritable.
- **Configuration & client contract**: no new config keys; clients publish/resolve exactly as before.

## 5. Verification

- Unit: artifact- and version-level XML shape + latest/release/lastUpdated + snapshot newest-build selection.
- HTTP: two independent publishers (each uploading only its own version + its own partial metadata) →
  served artifact metadata lists every version with matching checksums.
- Real-client round-trip: Maven + Gradle resolve a version range / `latest` and a `-SNAPSHOT` against
  multi-publisher state.

## Backward compatibility

Additive correctness. The served metadata stays valid Maven layout; the only observable change is that a
hosted listing no longer regresses when independent publishers contribute versions. No MAJOR version bump.
