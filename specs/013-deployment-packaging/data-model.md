# Phase 1 Data Model: Deployment Packaging for Self-Hosting

No application schema or class changes. The "model" is the inventory of deployment artifacts and the mapping
of the **existing** configuration/secret keys onto each runtime. Packaging consumes these keys; it defines
no new ones.

## Artifact inventory

| Artifact | Path | Purpose |
|----------|------|---------|
| Backend image | `deploy/backend.Dockerfile` | API server, JRE21, non-root, readiness HEALTHCHECK |
| Frontend image | `deploy/frontend.Dockerfile` | SvelteKit SPA on non-root nginx, proxies `/api`+repo paths to backend |
| Combined image | `deploy/combined.Dockerfile` | Backend with UI bundled under `/ui` (single origin) |
| nginx template | `deploy/nginx/default.conf.template` | SPA fallback + reverse proxy to `${RELIKQUARY_BACKEND}` |
| Compose | `deploy/docker-compose.yml` | backend (volume, auth on, healthcheck) + frontend; S3 commented |
| Env example | `deploy/.env.example` | placeholders only (publisher creds, optional S3) |
| K8s manifest | `deploy/k8s/relikquary.yaml` | ConfigMap, Secret(placeholders), PVC, backend+frontend Deploy/Svc, probes, limits |
| Smoke script | `deploy/smoke.sh` | Docker-guarded build+run+publish/resolve round-trip |
| Operator guide | `deploy/README.md` | build (split/combined), compose up, kubectl apply, switch storage |
| Build tasks | root `build.gradle.kts` | `dockerBuildBackend/Frontend/Combined/Split` Exec tasks |
| Validation test | `backend/src/test/.../deploy/DeploymentArtifactsTest.kt` | offline structure + no-secret assertions |

## Configuration keys consumed (existing — unchanged)

| Key (env / property) | Used by | Default in packaging |
|----------------------|---------|----------------------|
| `RELIKQUARY_STORAGE_ROOT` | backend, combined | `/data` (volume / PVC mount) |
| `relikquary.storage.backend` | backend, combined | `filesystem` (default); `s3` documented |
| `RELIKQUARY_S3_ENDPOINT` / `_REGION` / `_BUCKET` / `_ACCESS_KEY` / `_SECRET_KEY` | backend (S3 path) | placeholders in Secret / `.env` (commented) |
| `relikquary.security.enabled` | backend, combined | `true` (auth on) |
| `relikquary.security.users[0].{username,password,roles[0]}` | backend, combined | publisher supplied via env/Secret (placeholder) |
| `RELIKQUARY_MAVEN_CENTRAL_URL` | backend, combined | shipped default (Central) |
| `RELIKQUARY_GRADLE_PLUGIN_PORTAL_URL` | backend, combined | shipped default (portal) |
| `RELIKQUARY_BACKEND` | frontend (nginx) | `http://backend:8080` (compose) / backend Service (k8s) |
| `BASE_PATH` | frontend build / combined build | unset (`/`) standalone; `/ui` for combined (existing `npmBuild`) |

## Secret vs. non-secret split (FR-009, FR-010)

- **Non-secret** (ConfigMap / compose env): storage backend & root, proxy URLs, `RELIKQUARY_BACKEND`,
  auth-enabled flag, publisher *username*, resource hints.
- **Secret** (k8s Secret / `.env`, never committed): publisher *password*, S3 access/secret keys, any
  upstream proxy credentials. Committed files carry only placeholders (`changeme`, `${…}`), asserted by
  `DeploymentArtifactsTest`.

## Probe / port contract (reused, not redefined)

| Concern | Value |
|---------|-------|
| Backend HTTP port | `8080` |
| Liveness probe | `GET /actuator/health/liveness` |
| Readiness probe | `GET /actuator/health/readiness` |
| Container HEALTHCHECK (backend/combined) | readiness probe, `curl -f` |
| Frontend port | `8080` (nginx-unprivileged), exposed `8081` in compose |
| UI base path | `/` (standalone frontend) or `/ui` (combined) |

## Runtime topologies

1. **Split (compose/k8s default)**: clients → backend `:8080` (API + repo paths); humans → frontend, whose
   nginx proxies `/api` + repo paths to the backend. Filesystem storage on volume/PVC.
2. **Combined (option)**: one container serves API + `/ui` on a single origin; clients and humans share it.
3. **S3 (documented)**: either topology with `relikquary.storage.backend=s3` + `RELIKQUARY_S3_*` from
   Secret/env; no PVC required → backend can scale to multiple replicas.
