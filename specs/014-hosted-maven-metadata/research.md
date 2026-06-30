# Phase 0 Research: Server-Side maven-metadata.xml for Hosted Repositories

Decisions grounding the implementation. The strategy and snapshot-scope questions were settled in
`/speckit-clarify` (hybrid; full fidelity). The rest are implementation choices grounded in the existing code.

## D1 — Trigger: regenerate on publish of a hosted coordinate file (+ compute-on-read fallback)

**Decision**: In `RepositoryController.publish`, after a successful `storage.write` on `PublishDecision.ACCEPT`
for a **hosted** repo, if the published path is a real coordinate file (`classify()` ≠ `METADATA`), call
`HostedMetadataService.regenerate(repo, path)`. On the read side, when a GET targets a hosted repo's
`maven-metadata.xml` and the stored file is **absent**, compute it on the fly (and write it through), per the
hybrid clarify decision.

**Rationale**: Regeneration must follow the artifact landing in storage so enumeration sees it. Skipping the
metadata PUT avoids a feedback loop and lets the server override the client's upload. Compute-on-read covers
the "never published through this server yet / file missing" case so a listing is never absent.

**Alternatives considered**: Pure compute-on-read (rejected by clarify — adds enumeration to every metadata
GET). Regenerate only on POM publish (rejected: a publish may land the jar after the POM; regenerating on any
coordinate file is simplest and idempotent).

## D2 — Authoritative source: enumerate storage, ignore the uploaded version list

**Decision**: Build the version set from storage enumeration, not from the client-uploaded `maven-metadata.xml`.
Artifact dir = the published key minus `"/{version}/{fileName}"` (from `RepositoryPath.version`/`fileName`).
`storage.list(artifactDir)` yields the version directories; `storage.walk(artifactDir)` yields files +
`lastModified` for markers.

**Rationale**: The whole defect is that the uploaded list reflects only one publisher. Storage is the single
source of truth and is already enumerable on both backends (`list`, `walk`), as confirmed in the code survey.

**Alternatives considered**: Merge the uploaded list with the stored one — rejected: a malicious/stale upload
could still inject phantom versions; storage truth is simpler and correct.

## D3 — Artifact-level fields (latest / release / lastUpdated / versions)

**Decision**: `groupId`/`artifactId` derived from the path. `versions` = every version directory present.
`release` = the newest **release** (non-`-SNAPSHOT`) version; `latest` = the newest version overall;
"newest" is determined by the maximum file `lastModified` within each version dir (deploy recency, matching
Maven semantics). `lastUpdated` = the max `lastModified` across the artifact, formatted UTC `yyyyMMddHHmmss`.
`release`/`latest` are omitted when no applicable version exists (e.g. snapshot-only → no `<release>`).

**Rationale**: Maven's `latest`/`release` are "most recently deployed" markers; `lastModified` is the
available, backend-agnostic signal (`StoredObject.lastModified` from `walk`). Listing contents (not order)
drive range resolution; versions are emitted in deploy order (ascending `lastModified`).

**Alternatives considered**: Maven version-comparator ordering for `latest`/`release` — rejected: Maven uses
deploy recency, and mtime is the faithful, dependency-free signal already exposed by storage.

## D4 — Version-level snapshot metadata (full fidelity)

**Decision**: For a `-SNAPSHOT` version dir, build the version-level `maven-metadata.xml` with
`<versioning><snapshot><timestamp><buildNumber>` of the **newest build**, `<lastUpdated>`, and
`<snapshotVersions>` — one `<snapshotVersion>` per (classifier, extension) of that newest build, with
`<value>` = `{base}-{timestamp}-{buildNumber}`. Builds are parsed from filenames using the canonical
`YYYYMMDD.HHMMSS-N` token already used by `CleanupService` (`BUILD_TOKEN`).

**Rationale**: Cross-publisher snapshot resolution needs the server to advertise the newest build it actually
holds. Reusing the established timestamp token keeps one definition of the snapshot format. The newest build =
max by (timestamp, buildNumber).

**Alternatives considered**: Artifact-level only (rejected by clarify). Re-deriving a new snapshot format
(rejected — reuse `BUILD_TOKEN`; extract a tiny shared helper so cleanup and metadata agree).

## D5 — XML serialization & checksums: JDK-only, byte-faithful

**Decision**: Serialize with the JDK StAX writer (`javax.xml.stream.XMLOutputFactory`) into a deterministic
byte form; compute `.sha1` and `.md5` over those exact bytes via `MessageDigest` and write all three keys
(`maven-metadata.xml`, `.sha1`, `.md5`). Write order: metadata first, then its checksums.

**Rationale**: No new dependency (Constitution III). Generating checksums from the exact serialized bytes
guarantees FR-003 (client verification passes). StAX gives controlled, stable output.

**Alternatives considered**: A templating lib or Maven's own metadata model classes — rejected: new
dependencies and dependency-verification churn for a small, well-specified XML shape.

## D6 — Concurrency: per-coordinate in-process lock; convergence

**Decision**: Serialize `regenerate(...)` per groupId:artifactId with an in-process striped lock
(`ConcurrentHashMap<String, Any>` / `Striped`-style), so each regeneration's *enumerate-then-write* is atomic
relative to other regenerations of the same coordinate. Each publish writes its artifact **before** scanning,
so the last regeneration under the lock sees the full set. Per-file writes are atomic (filesystem temp-move).

**Rationale**: Without serialization, an early-scanning regeneration could write last and drop a concurrently
added version (FR-007). A per-coordinate lock is cheap and removes the race within one instance.

**Known limitation (documented)**: Across **multiple server instances** sharing one store, the in-process lock
doesn't serialize cross-instance. Mitigations: each subsequent publish regenerates from a full scan
(self-healing), and the read-side compute-on-read fallback recomputes when needed. A distributed lock is out
of scope here and noted for a future multi-replica hardening.

## D7 — Strictly hosted; proxy/group untouched

**Decision**: Regeneration and compute-on-read apply **only** when the target repo `kind == HOSTED`. Proxy and
group resolution paths are not modified; proxy metadata remains pass-through (never cached/generated).

**Rationale**: FR-006/SC-005. The publish hook is already inside the hosted-only branch; the read hook checks
`registry.require(repo).kind == HOSTED` before computing.

## D8 — Verification: real multi-publisher round-trip + unit + HTTP

**Decision**: (a) Unit-test the XML builder (artifact + version-level shapes, latest/release/lastUpdated,
snapshot newest-build selection); (b) an HTTP integration test that simulates two independent publishers —
each PUTting only its own version plus its own (partial) `maven-metadata.xml` — then asserts the served
artifact metadata lists **all** versions with matching checksums; (c) a real Maven + Gradle round-trip
resolving a version range / `latest` and a `-SNAPSHOT` against that multi-publisher state, reusing the
external-process harness (`PublishResolveRoundTripTest` style).

**Rationale**: Constitution II demands real-client proof; the two-publisher HTTP test directly targets the
defect; unit tests pin the XML invariants cheaply and offline.

**Alternatives considered**: Only a round-trip test — rejected: slower to pinpoint XML regressions; the unit +
HTTP layering localizes failures.
