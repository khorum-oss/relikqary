# Phase 0 Research: Deployment Packaging for Self-Hosting

Decisions resolving the packaging approach. The three product-level choices were settled in
`/speckit-clarify` (split images + combined option; artifacts-only; PVC default); the rest are
implementation decisions grounded in the existing build.

## D1 — Three image variants (backend, frontend, combined)

**Decision**: Ship `deploy/backend.Dockerfile`, `deploy/frontend.Dockerfile`, and
`deploy/combined.Dockerfile`. Backend = API only; frontend = the SPA served by nginx; combined = backend
with the UI bundled under `/ui`.

**Rationale**: Per clarify, split is primary (independent scaling, smaller blast radius, the API has no hard
dependency on the UI) with a combined convenience option. The split mirrors the existing module separation
(`backend/` + `frontend/`) and the dev topology (vite proxy → backend).

**Alternatives considered**: Single combined image only — rejected by clarify (operator wants split). One
Dockerfile with build args for all three modes — rejected: three small, readable Dockerfiles are clearer
than one branchy multi-target file for operators copying them.

## D2 — Base images (pinned, non-root, slim)

**Decision**:
- Backend/combined build stage: `eclipse-temurin:21-jdk`; runtime stage: `eclipse-temurin:21-jre`, add a
  non-root `relikquary` user, `HEALTHCHECK` via `curl -f http://localhost:8080/actuator/health/readiness`.
- Frontend build stage: `node:22`; serve stage: `nginxinc/nginx-unprivileged:stable-alpine` (runs as a
  non-root user listening on 8080 by default).

**Rationale**: Temurin JRE matches the project's JDK 21 toolchain and CI (temurin 21). `nginx-unprivileged`
gives a non-root static server without custom user wrangling. Pinned tags satisfy supply-chain integrity
(operators can pin digests further). JRE-only runtime keeps the backend image small.

**Alternatives considered**: Distroless (`gcr.io/distroless/java21:nonroot`) — smallest and non-root by
default, but has no shell/curl, so an in-image `HEALTHCHECK` is awkward; documented as an alternative for
operators who rely on orchestrator probes instead of container healthchecks. Alpine JRE (musl) — risk with
some native libs; avoided for the JVM image.

## D3 — Frontend image must reproduce the dev proxy

**Decision**: The frontend image serves the `adapter-static` SPA and **reverse-proxies `/api` and Maven
repository paths to the backend**, with the backend URL injected at container start via an nginx template
(`RELIKQUARY_BACKEND`, default `http://backend:8080`). SPA routes fall back to `index.html`
(`try_files … /index.html`).

**Rationale**: The SvelteKit app calls same-origin `/api` and repo paths (the vite dev server proxies these
to the backend — see `frontend/vite.config.*`). In production the static server must do the same, or the UI
can't reach the API. `nginx-unprivileged` supports `/etc/nginx/templates/*.template` + `envsubst` for the
backend URL. The combined image avoids this entirely (same origin).

**Alternatives considered**: Build the SPA to call an absolute backend URL — rejected: bakes the URL into
static assets, breaking the "configure without rebuild" requirement (FR-005). A Node `adapter-node` server —
rejected: the project already chose `adapter-static`; nginx is lighter for a static SPA.

## D4 — Standalone frontend build uses root base path; combined uses `/ui`

**Decision**: The standalone frontend image runs `npm ci && npm run build` with `BASE_PATH` **unset**
(serves at `/`). The combined image builds the backend with `-PbundleFrontend`, which runs the existing
`:frontend:npmBuild` task that sets `BASE_PATH=/ui` and copies the SPA into the jar's `static/ui`.

**Rationale**: `frontend/svelte.config.js` reads `BASE_PATH` for the app base; `frontend/build.gradle.kts`
`npmBuild` already pins `/ui` for the bundled case. Standalone served at root needs no base path. Reusing the
existing toggle means **zero build change** for the combined image.

**Alternatives considered**: Forcing `/ui` for the standalone image too — rejected: a standalone UI container
naturally serves at `/`.

## D5 — Build selection via root Gradle Exec tasks

**Decision**: Add root `build.gradle.kts` `Exec` tasks: `dockerBuildBackend`, `dockerBuildFrontend`,
`dockerBuildCombined`, and a `dockerBuildSplit` aggregate (backend + frontend). Each shells out to
`docker build -f deploy/<file> -t relikquary-<variant>:local .`. Tasks fail with a clear message if the
Docker CLI is absent.

**Rationale**: The operator asked for "a simple gradle task that allows the combined dockerfile build vs a
split option." `Exec` tasks need no new dependency and keep the one obvious entry point in the build the
team already uses. Image build context is the repo root so Dockerfiles can copy `backend/`, `frontend/`,
`gradle/`, wrapper, etc.

**Alternatives considered**: A shell script only — still provided implicitly via the Dockerfiles, but the
Gradle tasks are the requested ergonomic entry point. A Gradle Docker plugin (e.g. jib/bmuschko) — rejected:
new dependency + dependency-verification churn for no benefit over `docker build`.

## D6 — Compose topology and defaults

**Decision**: `deploy/docker-compose.yml` with two services: `backend` (built from `backend.Dockerfile`,
named volume `relikquary-store` → `/data`, `RELIKQUARY_STORAGE_ROOT=/data`, auth **on** with a publisher
supplied from `.env`, healthcheck on the readiness probe, port `8080:8080`) and `frontend` (built from
`frontend.Dockerfile`, `RELIKQUARY_BACKEND=http://backend:8080`, `depends_on` backend healthy, port
`8081:8080`). A commented block documents switching `backend` to S3 (`relikquary.storage.backend=s3` +
`RELIKQUARY_S3_*` from `.env`, drop the volume). `.env.example` carries placeholders only.

**Rationale**: Clients point at `:8080` (API + repo paths); humans use the UI at `:8081` (nginx proxies its
API/repo calls to `backend`). Auth-on + persistent volume are the safe defaults the spec requires. A single
`docker compose up` yields a working server + UI.

**Alternatives considered**: One combined service — documented as an alternative (`combined.Dockerfile`,
single port), but split is the compose default to match the chosen primary packaging.

## D7 — Kubernetes manifest shape

**Decision**: One `deploy/k8s/relikquary.yaml` (multi-document) containing: `ConfigMap` (non-secret env:
storage backend/root, proxy URLs defaults), `Secret` (placeholders: publisher password, S3 keys), `PVC`
(RWO, e.g. 10Gi), `Deployment` backend (1 replica; env from ConfigMap+Secret; PVC mounted at `/data`;
`livenessProbe`→`/actuator/health/liveness`, `readinessProbe`→`/actuator/health/readiness`; resource
requests/limits; `securityContext` runAsNonRoot + fsGroup so the PVC is writable), `Service` backend
(ClusterIP 8080), `Deployment` frontend (nginx image; `RELIKQUARY_BACKEND` → backend Service; probes on
`/`; resources), `Service` frontend, and a commented `Ingress` example routing `/` → frontend and
`/api` + repo paths → backend. Inline comments document the S3 swap (set backend=s3, fill the Secret, drop
the PVC, raise replicas) and the combined-image alternative.

**Rationale**: A single applyable file is the most useful "starting point." PVC + single replica is the
default per clarify (RWO can't be shared across nodes, hence single replica; S3 documented for multi-replica).
`fsGroup`/runAsNonRoot addresses the non-root volume-permission edge case.

**Alternatives considered**: Kustomize base/overlays — more idiomatic but heavier than a "general manifest";
deferred. Helm — out of scope per the request unless trivial; a chart is not trivially better than this
single manifest, so **deferred** (recorded in spec Assumptions).

## D8 — Verification: offline static test + Docker-guarded smoke

**Decision**:
- `DeploymentArtifactsTest` (backend test module, always runs offline): asserts the three Dockerfiles exist
  and declare a non-root user + a Java-21 base + an actuator-readiness `HEALTHCHECK` (backend/combined);
  the compose file enables auth and references the readiness healthcheck and a persistent volume; the k8s
  manifest contains Deployment/Service/ConfigMap/Secret/PVC, probes pointing at
  `/actuator/health/{liveness,readiness}`, and resource limits; and that **no committed artifact contains a
  real secret value** (placeholders/`${…}`/`changeme` only).
- `deploy/smoke.sh` (documented, Docker-guarded): if `docker` exists, build the backend image, run it with a
  temp volume + a publisher, then publish and resolve an artifact via curl and assert byte-equality; skip
  with a clear message otherwise.

**Rationale**: The static test makes the artifacts' safety/correctness invariants part of the normal green
build with no runtime needed (honoring Constitution III). The smoke gives a real publish/resolve through the
actual image where a runtime exists (honoring Constitution II's round-trip intent), guarded like the
project's other Docker-dependent tests. No CI publish/push is added (per clarify).

**Alternatives considered**: A Testcontainers-driven image test in the suite — rejected here: it needs Docker
in CI (absent), would flake, and the clarify answer was explicitly "artifacts only, maybe a local test, no
publishing." The guarded shell smoke + offline static test matches that intent.
