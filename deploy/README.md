# Deploying Relikquary

Operator guide for the deployment artifacts in this directory. Relikquary ships **two images** — a
backend (API) and a frontend (UI) — plus a **combined** single-image option. Nothing here is published to
a registry; you build the images locally (or push them to your own registry and update the references).

## Artifacts

| File | What it is |
|------|------------|
| `backend.Dockerfile` | API server image (JRE 21, non-root, readiness healthcheck) |
| `frontend.Dockerfile` | UI image (SvelteKit SPA on non-root nginx; proxies API/repo paths to the backend) |
| `combined.Dockerfile` | Single image serving API + UI (UI under `/ui`) |
| `nginx/default.conf.template` | Frontend reverse-proxy config (`${RELIKQUARY_BACKEND}`) |
| `docker-compose.yml` | Split backend + frontend, persistent volume, auth on (embedded SQLite app-state) |
| `docker-compose.postgres.yml` | Overlay: store application state in PostgreSQL instead of SQLite |
| `docker-compose.dev.yml` | Local development stack (Postgres + backend/frontend from source, auth off) |
| `.env.example` | Environment placeholders (copy to `.env`; never commit `.env`) |
| `k8s/relikquary.yaml` | Kubernetes Deployment/Service/ConfigMap/Secret/PVC starting point (with PostgreSQL) |
| `smoke.sh` | Docker-guarded build + publish/resolve smoke test |

## Build the images

From the repository root (the Gradle tasks wrap `docker build` with the repo as the build context):

```bash
./gradlew dockerBuildSplit      # backend + frontend (relikquary-backend:local, relikquary-frontend:local)
./gradlew dockerBuildCombined   # combined API+UI (relikquary:local)
# or a single one:
./gradlew dockerBuildBackend
```

These require the Docker CLI; they fail with a clear message if it is absent. For arm64 (or to target a
specific arch), build directly, e.g. `docker build --platform linux/arm64 -f deploy/backend.Dockerfile .`
(images are amd64 by default).

## Run with Docker Compose

```bash
cp deploy/.env.example deploy/.env       # set RELIKQUARY_PUBLISHER_PASSWORD (keep the {noop}/{bcrypt} prefix)
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build
```

- API + Maven repository protocol: `http://localhost:8080/<repo>/...` (point Maven/Gradle clients here).
- UI: `http://localhost:8081`.
- Data persists in the `relikquary-store` volume across restarts.

Publish/resolve example (auth is on):

```bash
printf 'bytes' | curl -u publisher:<pw> -H 'Content-Type: application/octet-stream' \
  -X PUT --data-binary @- http://localhost:8080/releases/com/example/app/1.0.0/app-1.0.0.jar
curl http://localhost:8080/releases/com/example/app/1.0.0/app-1.0.0.jar
```

### With PostgreSQL (application state)

By default, application state — API tokens, managed users, settings, publish history — is kept in an
embedded SQLite database on its own volume (`relikquary-db`). To store it in PostgreSQL instead, layer the
overlay (set `RELIKQUARY_DB_PASSWORD` in `.env` first):

```bash
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.postgres.yml \
               --env-file deploy/.env up -d --build
```

This adds a `postgres` service; the backend waits for it to be healthy and Hibernate creates the schema on
first boot. Artifact storage is unaffected. (Application state is *separate* from artifact storage —
choosing PostgreSQL here does not change where artifacts live.)

## Local development

A self-contained stack for hacking locally — PostgreSQL plus the backend and frontend built from source,
with **authentication disabled** and throwaway credentials (no secrets to set):

```bash
docker compose -f deploy/docker-compose.dev.yml up --build
# API http://localhost:8080 · UI http://localhost:8081 · Postgres localhost:5432 (relikquary/relikquary)
```

Iterating on the app itself? Start only Postgres and run the backend from your IDE / Gradle for fast
rebuilds:

```bash
docker compose -f deploy/docker-compose.dev.yml up -d postgres
RELIKQUARY_PERSISTENCE_BACKEND=postgres \
RELIKQUARY_DB_URL=jdbc:postgresql://localhost:5432/relikquary \
RELIKQUARY_DB_USER=relikquary RELIKQUARY_DB_PASSWORD=relikquary \
  ./gradlew :backend:bootRun
```

> The dev stack disables auth for convenience — never use `docker-compose.dev.yml` to expose a real server.

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

The same images run against either backend — **no rebuild**, just configuration:

- **Filesystem (default)**: artifacts live on the volume (`/data`) / PVC.
- **S3-compatible**: set `RELIKQUARY_STORAGE_BACKEND=s3` and the `RELIKQUARY_S3_*` values (via `.env` /
  Secret). In compose, drop the volume; in Kubernetes, remove the PVC and raise `replicas` (S3 is shared,
  the RWO PVC is not). See the commented blocks in `docker-compose.yml` and `k8s/relikquary.yaml`.

## Notes

- **Non-root volumes**: the backend runs as a non-root user. For host-bind mounts, ensure the mounted path
  is writable by that user; the compose named volume and the k8s `fsGroup` handle this automatically.
- **Secrets**: only placeholders are committed here. Never commit a real `.env`, Secret value, or password.
- **Verify locally** (where Docker exists): `bash deploy/smoke.sh` builds the backend image and round-trips
  a publish/resolve through it; it skips cleanly when no Docker runtime is present.
