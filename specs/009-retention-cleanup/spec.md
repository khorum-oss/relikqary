# Feature Specification: Retention & Cleanup Policies

**Feature Branch**: `009-retention-cleanup`

**Created**: 2026-06-28

**Status**: Draft

**Input**: User description: "Configurable retention/cleanup so storage doesn't grow unbounded: snapshot
retention (keep recent snapshot builds per artifact, purge older; never touch releases) and proxy cache
eviction (bound the cache by age/size; evicted artifacts safely re-fetched). Runs on a schedule and
on-demand, reports what it removed, supports dry-run. Opt-in per repository, no-op when unconfigured,
preserves faithful storage, works on filesystem and S3."

## Clarifications

### Session 2026-06-28

- Q: Proxy cache eviction "age" basis? → A: Cached-at — measured from the artifact's stored last-modified
  time (no per-request last-access tracking).
- Q: Which retention dimensions are configurable? → A: Snapshot repos: keep-last-N and/or maximum build
  age; proxy caches: maximum cached age and/or a total cache size budget. Each dimension is optional.
- Q: How is an on-demand run triggered? → A: An authorized management API endpoint (under `/api`) that
  runs cleanup (with an optional dry-run flag) and returns the report; consistent with the existing
  browse/manage API and auth model.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Snapshot retention keeps storage bounded (Priority: P1)

An operator configures a snapshot repository to keep only the most recent builds of each snapshot
artifact (and/or to purge builds older than a set age). When cleanup runs, older snapshot builds are
deleted and the newest are kept, so the repository stops growing without bound. Release repositories are
never touched.

**Why this priority**: Snapshot repositories accumulate a new timestamped build on every CI publish and
are the primary source of unbounded growth.

**Independent Test**: Publish several timestamped builds of one snapshot artifact, configure keep-last-N,
run cleanup, and confirm only the N newest builds remain and the artifact still resolves; a release repo
is unchanged.

**Acceptance Scenarios**:

1. **Given** a snapshot repo with retention "keep last N" and an artifact with more than N builds, **When**
   cleanup runs, **Then** only the N most recent builds remain and the older builds' files are deleted.
2. **Given** a snapshot repo with an age-based rule, **When** cleanup runs, **Then** builds older than the
   configured age are removed and newer ones kept.
3. **Given** a release repository, **When** any cleanup runs, **Then** nothing in it is removed or
   altered.
4. **Given** retention removed some builds, **When** a client resolves the snapshot artifact, **Then** it
   still resolves (the retained builds and version metadata remain consistent).

---

### User Story 2 - Proxy cache eviction bounds the cache (Priority: P1)

An operator configures a proxy repository to bound its local cache (evict artifacts older than a set age
and/or keep the cache under a size budget). When cleanup runs, cached artifacts selected by the policy
are evicted. Because a proxy can always re-fetch from its upstream, eviction loses no data — a later
request transparently re-fetches and re-caches the artifact.

**Why this priority**: Proxy caches grow with every distinct upstream artifact ever requested; bounding
them is the other main source of reclaimable space.

**Independent Test**: Cache several artifacts via a proxy, configure an eviction policy, run cleanup,
confirm the selected cached artifacts are gone, then request an evicted artifact and confirm it is
re-fetched and served.

**Acceptance Scenarios**:

1. **Given** a proxy with age-based eviction and a cached artifact older than the threshold, **When**
   cleanup runs, **Then** that cached artifact is removed.
2. **Given** a proxy with a cache size budget exceeded, **When** cleanup runs, **Then** cached artifacts
   are removed until the cache is within budget.
3. **Given** an evicted cached artifact, **When** a client requests it again, **Then** it is re-fetched
   from the upstream and served (no error, no data loss).

---

### User Story 3 - Scheduled, on-demand, dry-run, and reporting (Priority: P2)

Cleanup runs automatically on a configurable interval and can also be triggered on demand by an
authorized operator. Every run reports what it removed (counts and reclaimed space); a dry-run reports
the same selection without deleting anything, so an operator can preview the effect of a policy safely.

**Why this priority**: Operators need control and visibility — to schedule, to preview before enabling,
and to audit what was reclaimed.

**Independent Test**: Trigger an on-demand dry-run and confirm it reports the artifacts it would remove
while storage is unchanged; trigger a real run and confirm the report matches what was actually removed.

**Acceptance Scenarios**:

1. **Given** a configured schedule, **When** the interval elapses, **Then** cleanup runs automatically.
2. **Given** an authorized operator, **When** they trigger cleanup on demand, **Then** it runs and returns
   a report; an unauthorized request is refused.
3. **Given** a dry-run, **When** it runs, **Then** it reports the artifacts that would be removed and
   deletes nothing.
4. **Given** any run, **When** it completes, **Then** it reports the number of items removed and the space
   reclaimed.

### Edge Cases

- **Unconfigured repository**: a repo with no retention/eviction policy is never modified by cleanup
  (no-op); existing deployments are unaffected.
- **Metadata safety**: cleanup never deletes `maven-metadata.xml`, and retained builds plus version
  metadata stay consistent so resolution still works.
- **Never delete the newest**: snapshot retention always keeps at least the most recent build of an
  artifact, even under an aggressive age rule.
- **Releases immutable**: release coordinates are never selected for deletion.
- **Concurrent activity**: cleanup is safe to run while clients publish/resolve; an artifact evicted from
  a proxy and immediately requested is re-fetched.
- **Groups**: group repositories have no stored bytes of their own and are not subject to cleanup.
- **Partial/whole deletion**: cleanup removes only whole artifacts/builds the policy selects; it never
  truncates or rewrites the bytes of anything it keeps.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: A snapshot repository MAY be configured (opt-in) with snapshot retention that keeps the most
  recent builds of each snapshot artifact (a "keep last N" count and/or a maximum build age) and deletes
  the older builds.
- **FR-002**: Cleanup MUST never remove or alter anything in a release repository (releases are
  immutable).
- **FR-003**: Cleanup MUST never delete `maven-metadata.xml`, and the retained builds plus version
  metadata MUST remain consistent so the artifact still resolves after cleanup.
- **FR-004**: A proxy repository MAY be configured (opt-in) with cache eviction (a maximum cached-artifact
  age and/or a cache size budget) that removes cached artifacts the policy selects.
- **FR-005**: An artifact evicted from a proxy cache MUST be transparently re-fetched from the upstream on
  the next request (eviction loses no data).
- **FR-006**: Cleanup MUST run automatically on a configurable schedule and MUST also be triggerable on
  demand via an authorized management API endpoint (under `/api`) that returns the run's report.
- **FR-007**: Cleanup MUST support a dry-run mode that reports the artifacts/builds it would remove and
  the space it would reclaim, without deleting anything.
- **FR-008**: Every cleanup run MUST report what it removed — the number of artifacts/builds removed and
  the space reclaimed (or, for a dry-run, would remove/reclaim).
- **FR-009**: Retention/eviction MUST be a no-op for any repository without a configured policy; existing
  deployments MUST be unaffected.
- **FR-010**: Cleanup MUST delete only whole artifacts/builds selected by the policy and MUST never alter
  the stored bytes of anything it retains (faithful storage).
- **FR-011**: Cleanup MUST behave identically across the filesystem and S3 storage backends.
- **FR-012**: The on-demand trigger MUST require operator authorization (consistent with the existing
  auth model); an unauthorized request MUST be refused.

### Key Entities

- **Snapshot Retention Policy**: per-repository rule for snapshot repositories — a keep-last count and/or
  a maximum build age.
- **Cache Eviction Policy**: per-repository rule for proxy repositories — a maximum cached-artifact age
  and/or a cache size budget.
- **Snapshot Build**: a single timestamped build of a snapshot artifact (the set of files sharing one
  Maven snapshot timestamp/build-number), the unit of snapshot retention.
- **Cleanup Run**: an execution of cleanup (scheduled or on-demand, real or dry-run) that produces a
  report of items removed and space reclaimed.

## Success Criteria *(mandatory)*

- **SC-001**: With "keep last 3" on a snapshot repo holding 5 builds of an artifact, a cleanup leaves the
  3 newest builds and removes the 2 oldest, and the artifact still resolves.
- **SC-002**: A release repository is byte-for-byte unchanged after any cleanup run.
- **SC-003**: A proxy artifact selected by an eviction policy is removed by cleanup, and a subsequent
  request re-fetches and serves it successfully.
- **SC-004**: A dry-run reports the same selection a real run would remove, while storage is unchanged
  afterward.
- **SC-005**: Each run reports the count of removed items and bytes reclaimed; a deployment with no
  configured policies removes nothing.
- **SC-006**: Snapshot retention and proxy eviction produce identical results on the filesystem and S3
  backends.

## Assumptions

- **Snapshot build identity**: a build is identified by the Maven snapshot timestamp/build-number encoded
  in artifact filenames within a `-SNAPSHOT` version directory; all files sharing that build identifier
  are removed together. `maven-metadata.xml` is always retained.
- **Proxy eviction age basis**: "age" is measured from when an artifact was cached (its stored
  last-modified time); tracking per-request last-access times is out of scope for this feature.
- **Retention dimensions**: snapshot repositories use keep-last-N and/or maximum build age; proxy caches
  use maximum cached age and/or a total cache size budget. Each dimension is independently optional (a
  policy may set one or both); sensible defaults are documented.
- **Scheduling & trigger**: cleanup runs on a single configurable fixed interval; the on-demand trigger
  is an authorized management API endpoint (under `/api`) that accepts a dry-run flag and returns the
  report. Per-repository schedules are out of scope.
- **Mixed repositories**: snapshot retention applies only to snapshot coordinates; release coordinates in
  any repository are never touched.
- **Concurrency**: cleanup is best-effort and safe under concurrent publish/resolve; correctness does not
  depend on quiescing traffic.
- **Storage support**: relies on the existing storage abstraction's listing/deletion and per-entry
  size/last-modified metadata, which both backends already provide.
