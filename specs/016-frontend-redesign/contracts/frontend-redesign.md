# Contract: Frontend Redesign — "Artifact Sanctuary"

New HTTP surface is **additive** under `/api` (and `/api/admin` for privileged actions). The Maven
publish/resolve protocol, repository layout, checksums, and existing `/api` browse endpoints are
**unchanged** (Principle I). All new endpoints attach the session credential like today's browse calls.

## Phase 1 — no new contract

The reskin consumes the existing endpoints only:
`GET /api/repositories`, `GET /api/repositories/{repo}/contents/**`, `GET /api/repositories/{repo}/file/**`,
`GET /api/repositories/{repo}/module/**`, `DELETE /api/repositories/{repo}/**`, and Maven `GET`/`PUT
/{repo}/**`. No request/response shape changes.

## Phase 2 — read-only JSON

### `GET /api/stats`

| | |
|---|---|
| Auth | open (read), like browse |
| 200 | `{ "repositories": int, "artifacts": long, "storageBytes": long }` |

Figures reflect the periodically-refreshed storage-usage snapshot; the endpoint never walks storage
per request.

### `GET /api/catalog`

| Param | Meaning |
|-------|---------|
| `q` | filter by `group:artifact` substring (optional) |
| `repo` | scope to one repository (optional) |
| `page`, `pageSize` | pagination (bounded; server caps `pageSize`) |

| | |
|---|---|
| Auth | requires READ on the scoped repository/repositories |
| 200 | `{ "entries": CatalogEntry[], "page": int, "pageSize": int, "total": long, "truncated": bool }` |

`CatalogEntry`: `{ repository, group, artifact, latestVersion, versionCount, sizeBytes }`. One entry per
`group:artifact`. Proxy repos reflect cached content only.

| # | Given | When | Then |
|---|-------|------|------|
| C1 | several versions of one coordinate | catalog loads | exactly one entry for it, correct `latestVersion` + `versionCount` |
| C2 | a query `q` | catalog loads | only matching `group:artifact` entries returned |
| C3 | a very large repo | catalog loads | response is paginated/bounded; `truncated`/`total` disclose scope |

## Phase 3 — stateful admin (`/api/admin/**`, privileged)

### Tokens — `/api/admin/tokens`

| Method | Path | Body / Result |
|--------|------|---------------|
| POST | `/api/admin/tokens` | `{ name, scope }` → `201 { id, name, scope, createdAt, secret }` — `secret` returned **once** |
| GET | `/api/admin/tokens` | `200 [{ id, name, scope, createdAt, lastUsedAt, revoked }]` — never the secret |
| DELETE | `/api/admin/tokens/{id}` | `204` — revokes |

| # | Given | When | Then |
|---|-------|------|------|
| T1 | authorized user | POST a `PUBLISH` token | `secret` returned once; thereafter only masked |
| T2 | a `PUBLISH` token used as client credentials | publish an artifact | accepted where scope allows, denied where not |
| T3 | a token | DELETE it, then reuse it | subsequent auth rejected |

### Users — `/api/admin/users`

| Method | Path | Body / Result |
|--------|------|---------------|
| GET | `/api/admin/users` | `200 [{ id, username, email, roles, lastActiveAt }]` (managed users) |
| POST | `/api/admin/users` | `{ username, email?, password, roles }` → `201` |
| PATCH | `/api/admin/users/{id}` | partial update (roles/email/password) → `200` |
| DELETE | `/api/admin/users/{id}` | `204` |

| # | Given | When | Then |
|---|-------|------|------|
| U1 | admin | create a user with a role | the user authenticates and is governed by that role |
| U2 | a config (YAML) user | managed users exist | the config user still authenticates (no lockout) |
| U3 | non-admin | call any users endpoint | `403` |

### Activity — `GET /api/activity`

| | |
|---|---|
| Auth | read |
| 200 | `{ "events": [{ repository, group, artifact, version, principal, publishedAt }], ... }` newest first |

`principal` is `null` for anonymous publishes (render "unknown"). Per-artifact attribution is the latest
event for that `group:artifact`.

| # | Given | When | Then |
|---|-------|------|------|
| A1 | a publish by a known principal | activity loads | the event appears with its principal + time |
| A2 | an anonymous publish | recorded | `principal` is null/"unknown", not fabricated |

### Settings — `/api/admin/settings`

| Method | Path | Body / Result |
|--------|------|---------------|
| GET | `/api/admin/settings` | `200 { key: value, ... }` (allow-listed keys + current values) |
| PUT | `/api/admin/settings` | `{ key: value, ... }` → `200` or `400` (validation) |

| # | Given | When | Then |
|---|-------|------|------|
| S1 | admin | PUT a valid setting | persists across restart; effect observable |
| S2 | admin | PUT an invalid value | `400`; prior value retained |
| S3 | non-admin | PUT settings | `403` |

## Cross-cutting

- **Failure modes**: 401 (no/expired session) reopens the UI login; 403 (authorized-but-forbidden) shows a
  themed forbidden state; admin endpoints return **503** with a clear message when persistence is
  unavailable, while Phase-1/2 stateless features stay up (FR-020).
- **No protocol regression**: enabling tokens/persistence MUST NOT change any existing Maven/Gradle
  client's publish/resolve behavior or the existing `/api` browse contracts (Principle I).
