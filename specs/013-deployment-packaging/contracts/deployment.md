# Contract: Deployment Packaging

No new HTTP/wire contract. The surface is the operator-facing contract of the deployment artifacts: image
interfaces (ports, env, healthcheck, user), the compose/manifest shapes, and the secret-handling rule. App
behavior is the existing contract, unchanged.

## 1. Image contract

### Backend image (`deploy/backend.Dockerfile`)
- **Base**: build `eclipse-temurin:21-jdk`, runtime `eclipse-temurin:21-jre` (pinned).
- **User**: non-root (`relikquary`).
- **Port**: `EXPOSE 8080`.
- **Healthcheck**: `HEALTHCHECK CMD curl -f http://localhost:8080/actuator/health/readiness || exit 1`.
- **Config**: all via env (see data-model key table); `RELIKQUARY_STORAGE_ROOT` defaults to `/data`.
- **Entrypoint**: runs the Spring Boot jar.

### Frontend image (`deploy/frontend.Dockerfile`)
- **Base**: build `node:22`, serve `nginxinc/nginx-unprivileged:stable-alpine` (pinned).
- **User**: non-root (nginx-unprivileged default).
- **Port**: `EXPOSE 8080`.
- **Behavior**: serves the `adapter-static` SPA at `/`; reverse-proxies `/api` and Maven repository paths to
  `${RELIKQUARY_BACKEND}` (default `http://backend:8080`); SPA deep links fall back to `index.html`.
- **Healthcheck**: `HEALTHCHECK` on `/` (UI shell).

### Combined image (`deploy/combined.Dockerfile`)
- Backend built with `-PbundleFrontend` (UI under `/ui`, `BASE_PATH=/ui`); same base/user/port/healthcheck
  as the backend image; serves API + UI on one origin.

## 2. Build-task contract (root `build.gradle.kts`)

| Task | Effect |
|------|--------|
| `dockerBuildBackend` | `docker build -f deploy/backend.Dockerfile -t relikquary-backend:local .` |
| `dockerBuildFrontend` | `docker build -f deploy/frontend.Dockerfile -t relikquary-frontend:local .` |
| `dockerBuildCombined` | `docker build -f deploy/combined.Dockerfile -t relikquary:local .` |
| `dockerBuildSplit` | depends on `dockerBuildBackend` + `dockerBuildFrontend` |

Each fails with a clear message if the Docker CLI is unavailable. Build context is the repository root.

## 3. Compose contract (`deploy/docker-compose.yml`)

- `backend` service: built from `backend.Dockerfile`; named volume `relikquary-store`→`/data`;
  `relikquary.security.enabled=true` with a publisher from `.env`; healthcheck on readiness; published `8080:8080`.
- `frontend` service: built from `frontend.Dockerfile`; `RELIKQUARY_BACKEND=http://backend:8080`;
  `depends_on: backend (service_healthy)`; published `8081:8080`.
- Commented S3 alternative (set `relikquary.storage.backend=s3` + `RELIKQUARY_S3_*` from `.env`; drop volume).
- `deploy/.env.example`: placeholders only — `RELIKQUARY_PUBLISHER_USER`, `RELIKQUARY_PUBLISHER_PASSWORD=changeme`, optional S3 keys.

**Result**: `docker compose --env-file .env up` → API+repo at `:8080`, UI at `:8081`, data persisted, auth on.

## 4. Kubernetes contract (`deploy/k8s/relikquary.yaml`)

Multi-document manifest:
- `ConfigMap relikquary-config` — non-secret env (storage backend/root, proxy URLs, `RELIKQUARY_BACKEND`).
- `Secret relikquary-secrets` — **placeholders only** (`stringData` with `changeme`): publisher password, S3 keys.
- `PersistentVolumeClaim relikquary-data` — `ReadWriteOnce`, default size (e.g. 10Gi).
- `Deployment relikquary-backend` — 1 replica; env from ConfigMap + Secret; PVC at `/data`;
  `livenessProbe: /actuator/health/liveness`, `readinessProbe: /actuator/health/readiness`;
  resource requests/limits; `securityContext` runAsNonRoot + `fsGroup` for PVC write access.
- `Service relikquary-backend` — ClusterIP `:8080`.
- `Deployment relikquary-frontend` + `Service relikquary-frontend` — nginx image; `RELIKQUARY_BACKEND`
  → backend Service; probes on `/`; resource limits.
- Commented `Ingress` example: `/` → frontend, `/api` + repo paths → backend.
- Inline S3 + multi-replica + combined-image notes.

## 5. Secret-handling rule (FR-010 / SC-006)

No committed artifact contains a real secret value. Credentials appear only as: env references, `.env`
placeholders (`changeme`), or Secret `stringData` placeholders. Enforced by `DeploymentArtifactsTest`.

## 6. Verification contract

- **Offline (always)**: `DeploymentArtifactsTest` asserts the structural + no-secret invariants above as part
  of `./gradlew :backend:build`.
- **Docker-guarded (manual / where available)**: `deploy/smoke.sh` builds the backend image, runs it,
  publishes and resolves an artifact, asserts byte-equality; skips cleanly without Docker.

## Backward compatibility

Fully additive. No application source, build output, configuration key, probe endpoint, or client contract
changes. Removing `deploy/` returns the project to its prior state.
