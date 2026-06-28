# Research: Retention & Cleanup Policies

Phase 0 decisions for feature 009. The eviction age basis, policy dimensions, and on-demand trigger were
settled in the spec's `## Clarifications` (2026-06-28); this records the technical choices.

## D1 — Recursive storage enumeration

**Decision**: Add `walk(prefix): List<StoredObject>` to `ArtifactStorage`, returning every **file**
(recursively) under a prefix with its full key, size, and last-modified time
(`StoredObject(key, sizeBytes, lastModified)`). Filesystem implements it with `Files.walk`; S3 with a
paginated `listObjectsV2` (no delimiter).

**Rationale**: Cleanup must enumerate all snapshot builds / cached artifacts under a repo; the existing
`list(prefix)` is intentionally one-level (for browse). A dedicated recursive `walk` is the natural,
backend-uniform primitive and keeps cleanup logic out of the storage implementations.

**Alternatives**: repeatedly calling one-level `list` from the service — rejected: N+1 calls and
duplicated tree-walking logic per backend; S3 already enumerates recursively in one paginated call.

## D2 — Configuration model

**Decision**: Add an optional per-repository `retention` block plus a global `cleanup` block.

```yaml
relikquary:
  cleanup:
    enabled: false          # master switch for the scheduled runner (default off ⇒ no-op)
    interval: PT1H          # fixed delay between scheduled runs
  repositories:
    - name: snapshots
      type: snapshot
      retention:
        snapshot:
          keepLast: 5       # keep the N newest builds per artifact (optional)
          maxAge: P30D      # and/or purge builds older than this (optional)
    - name: maven-central
      kind: proxy
      remoteUrl: ...
      retention:
        cache:
          maxAge: P14D      # evict cached artifacts older than this (optional)
          maxSize: 5GB      # and/or keep the cache under this size budget (optional)
```

`RetentionPolicy(snapshot: SnapshotRetention?, cache: CacheEviction?)`;
`SnapshotRetention(keepLast: Int?, maxAge: Duration?)`; `CacheEviction(maxAge: Duration?, maxSize:
DataSize?)`. All fields optional; absent `retention` ⇒ that repo is never cleaned (FR-009).

**Rationale**: The clarified dimensions, co-located with the repository (consistent with features
004/006/007). `Duration`/`DataSize` bind natively from Spring config (`P30D`, `5GB`). No new dependency.

## D3 — Snapshot build identity & selection

**Decision**: Within a `-SNAPSHOT` version directory, group files by the Maven snapshot
**timestamp-buildnumber** token in the filename (`…-yyyyMMdd.HHmmss-N.ext`); that group is one *build*.
Order builds by their parsed timestamp. Select for deletion the builds beyond `keepLast` (oldest first)
and/or older than `maxAge`, but **always keep at least the newest build**. Files without a timestamp
token (a plain `…-SNAPSHOT.ext`, `maven-metadata.xml`, checksums of retained builds) are never selected.

**Rationale**: FR-001/FR-003 and the "never delete the newest / never delete metadata" edge cases.
Grouping by the timestamp token is exactly how Maven distinguishes unique snapshot builds.

**Alternatives**: deleting by individual file age — rejected: would split a build (jar kept, pom purged),
breaking resolution.

## D4 — Proxy cache eviction selection

**Decision**: `walk` the proxy's prefix; treat every cached file as evictable (a proxy never caches
`maven-metadata.xml`, feature 006). Select by **age** (last-modified older than `maxAge`) and, for
**size**, sort remaining files oldest-first and evict until the total is within `maxSize`. Evicted files
are deleted; FR-005 holds because the resolver re-fetches on the next request.

**Rationale**: The clarified cached-at age basis (storage `lastModified`, no access tracking) and size
budget. Re-fetch safety is already guaranteed by the proxy resolver (006).

## D5 — Cleanup service & report

**Decision**: A `CleanupService.run(dryRun): CleanupReport` iterates configured repositories, applies the
relevant policy by kind (snapshot retention for hosted snapshot/mixed repos; cache eviction for proxy
repos), and either deletes the selected files (`storage.delete`) or, in dry-run, only records them.
`CleanupReport` aggregates per-repository results (items removed, bytes reclaimed) and totals, plus the
`dryRun` flag.

**Rationale**: One orchestrator with pure selection helpers (unit-testable) and a single mutation point
keeps faithful storage easy to audit (FR-010).

## D6 — Scheduling

**Decision**: Add `@EnableScheduling` to the application. A `CleanupScheduler` component, gated by
`@ConditionalOnProperty("relikquary.cleanup.enabled")`, runs `@Scheduled(fixedDelayString =
"${relikquary.cleanup.interval-ms}")` calling `CleanupService.run(dryRun = false)`. Disabled by default
⇒ no scheduled runs unless opted in.

**Rationale**: FR-006. Spring scheduling is part of `spring-context` (already on the classpath) — no new
dependency. Gating by config keeps existing deployments unaffected.

## D7 — On-demand endpoint & authorization

**Decision**: A `CleanupController` exposes `POST /api/cleanup?dryRun={bool}` returning the
`CleanupReport` as JSON. Authorization: extend the feature-007 `RepositoryAuthorizationManager` to
require the global `PUBLISH` authority for the management path `/api/cleanup` (it currently grants
non-repo `/api` paths). When `security.enabled=false`, the manager isn't wired and the endpoint is open
(consistent with the local-dev bypass).

**Rationale**: FR-006/FR-012. Reusing the existing manager keeps authorization in one place and yields
correct `401`/`403`; method-security annotations were rejected because they would also fire when security
is globally disabled, breaking the bypass.

## D8 — Dependencies & testing

**Decision**: **No new dependencies** (`@EnableScheduling`, `Duration`, `DataSize` are all
Spring/JDK built-ins) — `gradle/verification-metadata.xml` is untouched. Tests (Principle II):
- Unit: snapshot build grouping/selection (keepLast, maxAge, keep-newest); cache eviction selection
  (age, size budget).
- Integration (`@SpringBootTest` + `HttpClient`): seed several timestamped snapshot builds via PUT,
  trigger `POST /api/cleanup`, assert older builds removed / newest kept / artifact still resolves;
  proxy size-budget + maxAge eviction against a stub upstream, then re-fetch; a release repo unchanged;
  dry-run leaves storage intact but reports; `/api/cleanup` auth matrix.
- Storage parity: `walk` covered for filesystem (unit/`@TempDir`) and S3 (extend the s3mock harness),
  honoring FR-011.

**Rationale**: Real round-trips over the HTTP + storage boundary; both backends exercised for the new
`walk` primitive.
