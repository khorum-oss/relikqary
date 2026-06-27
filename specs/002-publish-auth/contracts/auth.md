# Contract Delta: Authentication on the Repository Protocol

Extends `specs/001-publish-resolve-mvp/contracts/repository-http.md`. Only authentication behaviour is
added; paths, layout, and stored bytes are unchanged.

## When auth is enabled (`relikquary.security.enabled=true`, default)

- **PUT `/{path}`** — requires HTTP Basic credentials for a user holding the `PUBLISH` role.
  - missing/invalid credentials → `401 Unauthorized` with `WWW-Authenticate: Basic realm="relikquary"`;
    nothing is stored.
  - authenticated but lacking `PUBLISH` → `403 Forbidden`; nothing is stored.
  - valid publisher → unchanged publish behaviour (`201`/`200`/`409` per the 001 contract).
- **GET / HEAD `/{path}`** — unchanged; no credentials required (open read). Presenting credentials is
  accepted and does not change the response.

## When auth is disabled (`relikquary.security.enabled=false`)

- All methods behave exactly as in the 001 contract; no credentials are required for `PUT`.

## Client configuration

- **Gradle publish**: `maven { url = uri(<relikquary>); credentials { username = …; password = … } }`.
- **Maven deploy/resolve**: a `<server>` entry in `settings.xml` matching the repository id.
- **Resolve**: no credentials needed (open read).
