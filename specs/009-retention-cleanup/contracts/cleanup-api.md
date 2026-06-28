# Contract: Retention & Cleanup

Adds opt-in per-repository retention/eviction policies and an on-demand cleanup endpoint. The Maven wire
protocol, resolution, and faithful storage are unchanged.

## Configuration

```yaml
relikquary:
  cleanup:
    enabled: true            # run cleanup on a schedule (default false ⇒ scheduled runs off)
    interval: PT1H           # fixed delay between scheduled runs
  repositories:
    - name: snapshots
      type: snapshot
      retention:
        snapshot:
          keepLast: 5        # keep the N newest builds per artifact
          maxAge: P30D       # and/or purge builds older than this
    - name: maven-central
      kind: proxy
      remoteUrl: https://repo1.maven.org/maven2
      retention:
        cache:
          maxAge: P14D       # evict cached artifacts older than this
          maxSize: 5GB       # and/or keep the cache within this size budget
```

A repository without a `retention` block is never cleaned. Release repositories are never modified.

## On-demand endpoint

### POST /api/cleanup?dryRun={bool}
Runs cleanup once and returns a report. `dryRun=true` computes and reports the selection without deleting.

```json
{
  "dryRun": false,
  "itemsRemoved": 12,
  "bytesReclaimed": 3456789,
  "repositories": [
    { "name": "snapshots", "itemsRemoved": 8, "bytesReclaimed": 3400000 },
    { "name": "maven-central", "itemsRemoved": 4, "bytesReclaimed": 56789 }
  ]
}
```

- Requires the `PUBLISH` authority when `relikquary.security.enabled=true` (`401` unauthenticated, `403`
  authenticated-without-role); open when security is disabled.

## Selection rules

- **Snapshot retention** (hosted snapshot/mixed repos): builds are grouped by the Maven
  timestamp-buildnumber (`…-yyyyMMdd.HHmmss-N.ext`); builds beyond `keepLast` (oldest first) and/or older
  than `maxAge` are removed, but the newest build of each artifact is always kept. `maven-metadata.xml`
  and non-timestamped files are never removed.
- **Proxy cache eviction** (proxy repos): cached files older than `maxAge` are removed; for `maxSize`, the
  oldest cached files are removed until the cache total is within budget. Evicted artifacts re-fetch from
  the upstream on the next request.

## Invariants

- Release repositories and `maven-metadata.xml` are never deleted; retained artifacts still resolve.
- Cleanup deletes only whole selected builds/files and never alters the bytes of anything it keeps
  (Principle IV).
- Behaviour is identical across the filesystem and S3 backends.
- A dry-run never mutates storage.
