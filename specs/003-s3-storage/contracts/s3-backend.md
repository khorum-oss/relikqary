# Contract: S3-Compatible Storage Backend

Extends the 001 repository contract. The HTTP wire protocol, Maven layout, and auth (002) are
**unchanged**; only the persistence target changes when `relikquary.storage.backend=s3`.

## Object mapping

- The validated Maven-layout request path (`RepositoryPath`, no leading slash, no traversal) is used
  **directly** as the S3 object key. Example: `PUT /com/example/widget/1.0.0/widget-1.0.0.jar` →
  object key `com/example/widget/1.0.0/widget-1.0.0.jar` in the configured bucket.

## Operations (via AWS SDK v2)

- **write** (`PUT`) → `putObject(bucket, key, bytes)`; bytes stored byte-for-byte (FR-004).
- **read** (`GET`) → `getObject(bucket, key)`; streamed back with the object's content length.
- **exists** (`HEAD`) → `headObject(bucket, key)`; `NoSuchKey`/404 ⇒ absent.

## Invariants

- **Faithful bytes**: object content equals the published bytes; nothing is re-encoded or re-checksummed.
- **Absent ⇒ 404**: a missing key surfaces as a clean 404 (not 5xx), preserving repository fall-through.
- **No protocol change**: republish policy (001) and auth (002) behave identically regardless of backend.

## Configuration

```yaml
relikquary:
  storage:
    backend: s3
    s3:
      endpoint: https://nyc3.digitaloceanspaces.com   # or a MinIO/custom endpoint
      region: us-east-1
      bucket: my-artifacts
      access-key: ${RELIKQUARY_S3_ACCESS_KEY}
      secret-key: ${RELIKQUARY_S3_SECRET_KEY}
      path-style-access: true
```

Credentials come from environment/configuration and are never committed (FR-007, Principle IV).
