# HTTP request collection

IntelliJ HTTP Client (`.http`) requests covering every Relikquary endpoint.

## Files

| File | Covers |
|------|--------|
| `publish.http` | `PUT /{repo}/{path}` — release/snapshot/metadata publishes, immutability (409), type mismatch (400), unknown repo (404), invalid path (400), anonymous publish (401) |
| `resolve.http` | `GET` / `HEAD /{repo}/{path}` — resolve (200), missing artifact (404), unknown repo (404), invalid path (400) |
| `http-client.env.json` | Shared environments (`local`, `secured`): `baseUrl`, `publishUser`. Committed. |
| `http-client.private.env.json` | The publish **password**. Gitignored — never committed. |

## Setup

1. Start the backend. For friction-free testing use the auth-disabled `local` profile:
   ```sh
   ./gradlew :backend:bootRun --args='--spring.profiles.active=local'
   ```
   To exercise auth instead, run the default profile with a configured publisher
   (`relikquary.security.users`) and select the `secured` environment.
2. Copy your publish password into `http-client.private.env.json` (the value WITHOUT its
   `{noop}`/`{bcrypt}` encoder prefix). For the `local` profile any value works — auth is off.
3. Open a `.http` file, pick an environment in the run gutter, and send requests.

## Notes

- `resolve.http` assumes the artifacts from `publish.http` already exist — run `publish.http` first.
- Each request carries a `client.test(...)` assertion on the expected status, so "Run all" doubles as
  a smoke test of the protocol.
