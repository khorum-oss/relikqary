# Data Model: Frontend Redesign — "Artifact Sanctuary"

Phases 1–2 introduce **no persisted entities** — they reuse existing storage/registry data and add
read-only derived DTOs. Phase 3 introduces the project's first persisted tables.

## Phase 2 — derived DTOs (computed, not stored)

### StatsResponse (`GET /api/stats`)

| Field | Type | Source |
|-------|------|--------|
| `repositories` | int | repository registry size |
| `artifacts` | long | storage-usage snapshot (object count) — same source as `relikquary.storage.objects` |
| `storageBytes` | long | storage-usage snapshot (bytes) — same source as `relikquary.storage.usage.bytes` |

Read-only; reflects the periodically-refreshed snapshot (not per-request precision).

### CatalogEntry (`GET /api/catalog`)

| Field | Type | Description |
|-------|------|-------------|
| `repository` | string | repo the coordinate lives in |
| `group` | string | groupId |
| `artifact` | string | artifactId |
| `latestVersion` | string | newest version by the existing version-ordering |
| `versionCount` | int | number of versions under the coordinate |
| `sizeBytes` | long | aggregate size across the coordinate's files |

Paginated response: `{ entries: CatalogEntry[], page, pageSize, total, truncated? }`. `?q=` filters by
`group:artifact` substring; `?repo=` scopes to one repository. Proxy repos reflect cached content only.

## Phase 3 — persisted entities

> Backend-agnostic across SQLite (default) and Postgres; types below are logical. Schema is created/
> evolved by Flyway migrations.

### ApiToken (NEW)

| Field | Type | Notes |
|-------|------|-------|
| `id` | uuid/string (PK) | |
| `name` | string | user-facing label, unique per owner |
| `ownerUsername` | string | principal the token acts as (managed or config user) |
| `secretHash` | string | salted hash of the secret; **the secret itself is never stored** |
| `scope` | enum(`READ`,`PUBLISH`) | maps to existing `Action` authorization |
| `createdAt` | timestamp | |
| `lastUsedAt` | timestamp? | updated on successful auth |
| `revokedAt` | timestamp? | non-null ⇒ revoked |

**Invariants**: secret shown exactly once at creation (FR-015); a revoked or unknown token authenticates
nothing; `lastUsedAt` advances monotonically; lookup is by hash, never by plaintext.

### ManagedUser (NEW)

| Field | Type | Notes |
|-------|------|-------|
| `id` | uuid/string (PK) | |
| `username` | string (unique) | must not collide with a config user (validated) |
| `email` | string? | display only |
| `passwordHash` | string | delegating-encoder format (`{bcrypt}…`) |
| `roles` | set<string> | reuse existing role model |
| `lastActiveAt` | timestamp? | updated on authenticated activity |
| `createdAt` | timestamp | |

**Invariants**: managed users coexist with `InMemoryUserDetailsManager` config users (FR-016, no lockout);
admin CRUD writes the DB only and never mutates YAML config users.

### PublishEvent (NEW)

| Field | Type | Notes |
|-------|------|-------|
| `id` | uuid/string (PK) | |
| `repository` | string | |
| `group` | string | |
| `artifact` | string | |
| `version` | string | |
| `fileName` | string? | the published file, if recorded |
| `principal` | string? | null ⇒ anonymous/unknown (rendered "unknown", never fabricated) |
| `publishedAt` | timestamp | |

**Invariants**: recorded **after** a successful store; recording failure MUST NOT fail or alter the
publish (Principle IV). Indexed by `(group, artifact)` for attribution and by `publishedAt` for recency.

### Setting (NEW)

| Field | Type | Notes |
|-------|------|-------|
| `key` | string (PK) | from a fixed allow-list of safe keys |
| `value` | string | validated per key |
| `updatedAt` | timestamp | |
| `updatedBy` | string? | admin who changed it |

**Invariants**: only allow-listed keys exist; admin-only writes; validation rejects bad values with the
prior value retained (FR-018 / US10-scenario-4).

## Relationships

- `ApiToken.ownerUsername` and `PublishEvent.principal` reference a username that may be a `ManagedUser`
  **or** a config user — they are not FKs into `ManagedUser` (config users have no row). Render
  attribution by username; "unknown" when null.
- `Setting` is standalone (key/value).

## Authorization touchpoints (no new entity)

- Token `scope` and user `roles` flow into the **existing** `RepositoryAuthorizer`/`Action` decision —
  no parallel authorization model is introduced.
