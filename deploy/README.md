# Deploying Relikquary

Everything you need to run Relikquary — from a one-command local try-out to a Kubernetes deployment.
Relikquary ships **two images** (a backend/API and a frontend/UI) plus a **combined** single-image
option. Nothing is published to a registry; you build the images locally (or push them to your own).

**Two kinds of storage, kept separate:**

- **Artifact storage** — the jars/poms themselves: filesystem volume (default) or S3.
- **Application state** — API tokens, users, settings, publish history: embedded **SQLite** (default) or
  external **PostgreSQL**.

---

## Quick start — try it locally (one command)

The fastest way to see it running. Needs **Docker** (Docker Desktop, or a running daemon) and free ports
**8080 / 8081 / 5432**. Auth is disabled and credentials are throwaway — nothing to configure.

```bash
docker compose -f deploy/docker-compose.dev.yml up --build
```

The first run builds the images (a few minutes: the backend jar + the UI). It starts PostgreSQL, waits
for it, then the backend, then the UI. You're up when the backend logs `Started RelikquaryApplication`.

| Open this | Where |
|-----------|-------|
| **Web UI** | http://localhost:8081 |
| **API + Maven repositories** | http://localhost:8080 |
| **PostgreSQL** | `localhost:5432` — db `relikquary`, user `relikquary`, password `relikquary` |

**Is it working?** In another terminal:

```bash
docker compose -f deploy/docker-compose.dev.yml ps        # all services "running"/"healthy"

curl -s http://localhost:8080/api/stats                   # -> {"repositories":...}

# publish an artifact (auth is off here) and read it back
printf 'hello' | curl -s -H 'Content-Type: application/octet-stream' \
  -X PUT --data-binary @- http://localhost:8080/releases/com/example/app/1.0.0/app-1.0.0.jar
curl -s http://localhost:8080/releases/com/example/app/1.0.0/app-1.0.0.jar   # -> hello

# see the tables Hibernate created in PostgreSQL
docker compose -f deploy/docker-compose.dev.yml exec postgres \
  psql -U relikquary -d relikquary -c '\dt'                # -> api_token, setting
```

Then open **http://localhost:8081**, click around the sidebar, and try refreshing on a page like
`/dashboard` — it should reload the app, not error.

**Stop it:**

```bash
docker compose -f deploy/docker-compose.dev.yml down       # stop, keep data
docker compose -f deploy/docker-compose.dev.yml down -v     # stop and wipe the volumes (fresh start)
```

> ⚠️ The dev stack disables authentication for convenience — never use `docker-compose.dev.yml` to
> expose a real server. For an auth-on setup, see **Run with Docker Compose** below.

### Reading the logs / "is the frontend broken?"

The frontend is **nginx**, which logs at `notice`/`info` level — so a healthy startup looks noisy but is
fine. These lines are all **normal**, not errors:

```
/docker-entrypoint.sh: /docker-entrypoint.d/ is not empty, will attempt to perform configuration
10-listen-on-ipv6-by-default.sh: info: /etc/nginx/conf.d/default.conf differs from the packaged version
20-envsubst-on-templates.sh: Running envsubst on /etc/nginx/templates/default.conf.template ...
/docker-entrypoint.sh: Configuration complete; ready for start up
... start worker process ...
```

Once you see **`Configuration complete; ready for start up`** and worker processes, the UI is serving. A
*real* problem shows up as the image failing to **build** or a container that keeps restarting — check:

```bash
docker compose -f deploy/docker-compose.dev.yml logs -f frontend   # or backend / postgres
docker compose -f deploy/docker-compose.dev.yml build frontend     # surfaces build errors on their own
```

### Faster iteration (native, no image rebuilds)

Run only PostgreSQL in Docker and the apps natively for instant reloads:

```bash
docker compose -f deploy/docker-compose.dev.yml up -d postgres      # just the database

# backend (new terminal)
RELIKQUARY_PERSISTENCE_BACKEND=postgres \
RELIKQUARY_DB_URL=jdbc:postgresql://localhost:5432/relikquary \
RELIKQUARY_DB_USER=relikquary RELIKQUARY_DB_PASSWORD=relikquary \
  ./gradlew :backend:bootRun

# frontend with hot reload (new terminal) — vite proxies /api + repo paths to :8080
cd frontend && npm install && npm run dev                           # http://localhost:5173
```

---

## Artifacts

| File | What it is |
|------|------------|
| `docker-compose.dev.yml` | **Local dev stack** — Postgres + backend/frontend from source, auth off |
| `docker-compose.yml` | Split backend + frontend, persistent volume, **auth on** (embedded SQLite app-state) |
| `docker-compose.postgres.yml` | Overlay for the above: application state in PostgreSQL instead of SQLite |
| `.env.example` | Environment placeholders (copy to `.env`; never commit `.env`) |
| `backend.Dockerfile` | API server image (JRE 21, non-root, readiness healthcheck) |
| `frontend.Dockerfile` | UI image (SvelteKit SPA on non-root nginx; proxies API/repo paths to the backend) |
| `combined.Dockerfile` | Single image serving API + UI (UI under `/ui`) |
| `nginx/default.conf.template` | Frontend reverse-proxy config (`${RELIKQUARY_BACKEND}`) |
| `k8s/relikquary.yaml` | Kubernetes manifest (backend + frontend + PostgreSQL) starting point |
| `smoke.sh` | Docker-guarded build + publish/resolve smoke test |

## Build the images

The dev/compose commands above build automatically (`--build`). To build the images by name yourself,
from the repository root:

```bash
./gradlew dockerBuildSplit      # backend + frontend (relikquary-backend:local, relikquary-frontend:local)
./gradlew dockerBuildCombined   # combined API+UI (relikquary:local)
./gradlew dockerBuildBackend    # just one
```

These require the Docker CLI; they fail with a clear message if it is absent. For arm64 (or another arch),
build directly, e.g. `docker build --platform linux/arm64 -f deploy/backend.Dockerfile .` (amd64 by default).

## Run with Docker Compose (auth on)

The production-shaped stack: authentication enabled, a real publisher account, storage on a named volume.

```bash
cp deploy/.env.example deploy/.env       # set RELIKQUARY_PUBLISHER_PASSWORD (keep the {noop}/{bcrypt} prefix)
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build
```

- API + Maven repository protocol: `http://localhost:8080/<repo>/...` (point Maven/Gradle clients here).
- UI: `http://localhost:8081`.
- Artifacts persist in the `relikquary-store` volume; application state (SQLite) in `relikquary-db`.

Publish/resolve example (auth is on):

```bash
printf 'bytes' | curl -u publisher:<pw> -H 'Content-Type: application/octet-stream' \
  -X PUT --data-binary @- http://localhost:8080/releases/com/example/app/1.0.0/app-1.0.0.jar
curl http://localhost:8080/releases/com/example/app/1.0.0/app-1.0.0.jar
```

### With PostgreSQL (application state)

By default, application state — API tokens, users, settings, publish history — lives in an embedded SQLite
database on its own volume (`relikquary-db`). To store it in PostgreSQL instead, layer the overlay (set
`RELIKQUARY_DB_PASSWORD` in `.env` first):

```bash
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.postgres.yml \
               --env-file deploy/.env up -d --build
```

This adds a `postgres` service; the backend waits for it to be healthy and Liquibase creates the schema on
first boot. Artifact storage is unaffected — choosing PostgreSQL here does not change where artifacts live.

## Deploy to Kubernetes

```bash
# Build the images and make them available to your cluster (load into the node, or push to a registry
# and edit the image: fields in k8s/relikquary.yaml). Then set real Secret values and apply:
kubectl apply -n <namespace> -f deploy/k8s/relikquary.yaml
kubectl rollout status deploy/relikquary-backend -n <namespace>
```

The manifest wires liveness/readiness probes to `/actuator/health/liveness` and `/actuator/health/readiness`,
separates non-secret config (ConfigMap) from credentials (Secret — **placeholders only**, replace before
use), and persists storage on a `ReadWriteOnce` PVC (single backend replica). An `Ingress` example is
included (commented) to route the UI and the API/repository paths.

It also deploys **PostgreSQL** (Deployment + Service + PVC) for application state, wired to the backend via
the ConfigMap/Secret; an init container makes the backend wait for the database before starting. Set the
`RELIKQUARY_DB_PASSWORD` Secret (shared by Postgres and the backend) before applying. To use embedded
SQLite instead (single replica), set `RELIKQUARY_PERSISTENCE_BACKEND=sqlite` and `RELIKQUARY_DB_PATH` to a
path on the data PVC in the ConfigMap, and delete the three `relikquary-postgres*` resources.

## Storage backends (filesystem ↔ S3)

The same images run against either **artifact** backend — no rebuild, just configuration:

- **Filesystem (default)**: artifacts live on the volume (`/data`) / PVC.
- **S3-compatible**: set `RELIKQUARY_STORAGE_BACKEND=s3` and the `RELIKQUARY_S3_*` values (via `.env` /
  Secret). In compose, drop the volume; in Kubernetes, remove the PVC and raise `replicas` (S3 is shared,
  the RWO PVC is not). See the commented blocks in `docker-compose.yml` and `k8s/relikquary.yaml`.

## Troubleshooting

- **`port is already allocated` / address in use** — something else is on 8080/8081/5432. Stop it, or
  change the left-hand side of the `ports:` mapping (e.g. `"18080:8080"`).
- **Frontend logs look like errors** — nginx logs at `notice`/`info`; see *Reading the logs* above. If it
  truly won't come up, run `docker compose ... build frontend` to see build errors on their own.
- **Backend keeps restarting on the Postgres stack** — it waits for the database; give it a few seconds.
  If it persists, check `docker compose ... logs postgres` (wrong/blank `RELIKQUARY_DB_PASSWORD`?).
- **Changed code isn't reflected** — rebuild: add `--build` (compose) or re-run the `dockerBuild*` task.
- **Start completely fresh** — `docker compose ... down -v` wipes the named volumes (artifacts + database).

## Notes

- **Non-root volumes**: the backend runs as a non-root user. For host-bind mounts, ensure the path is
  writable by that user; the compose named volumes and the k8s `fsGroup` handle this automatically.
- **Secrets**: only placeholders are committed here. Never commit a real `.env`, Secret value, or password.
- **Verify locally** (where Docker exists): `bash deploy/smoke.sh` builds the backend image and round-trips
  a publish/resolve through it; it skips cleanly when no Docker runtime is present.
