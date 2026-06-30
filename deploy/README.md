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
| `docker-compose.yml` | Split backend + frontend, persistent volume, auth on |
| `.env.example` | Environment placeholders (copy to `.env`; never commit `.env`) |
| `k8s/relikquary.yaml` | Kubernetes Deployment/Service/ConfigMap/Secret/PVC starting point |
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
