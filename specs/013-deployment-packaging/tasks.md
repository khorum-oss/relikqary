---
description: "Task list for Deployment Packaging for Self-Hosting"
---

# Tasks: Deployment Packaging for Self-Hosting

**Input**: Design documents from `specs/013-deployment-packaging/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/deployment.md, quickstart.md

**Tests**: Included — FR-014 requires verification: an always-on offline `DeploymentArtifactsTest` plus a
Docker-guarded `smoke.sh`. No registry/CI publish is added (per clarify).

**Organization**: By user story (US1 run-as-container P1, US2 Kubernetes P2, US3 storage switch P3). No
application/build/config/client source changes — all artifacts live under a new `deploy/` tree, with additive
root Gradle Exec tasks and one Kotlin validation test.

## Path Conventions

New `deploy/` tree at repo root; root `build.gradle.kts` for build tasks; validation test under
`backend/src/test/kotlin/org/khorum/oss/relikquary/deploy/`. Reuse facts: JDK 21 (temurin); SvelteKit
`adapter-static` (`npm run build` → `frontend/build`, `BASE_PATH` controls base — `npmBuild` pins `/ui`);
backend port 8080; probes `/actuator/health/{liveness,readiness}`; env keys in data-model.md.

---

## Phase 1: Setup

- [X] T001 Create the `deploy/` tree skeleton: `deploy/` and `deploy/nginx/` and `deploy/k8s/` directories, and `deploy/.env.example` containing placeholders only (`RELIKQUARY_PUBLISHER_USER`, `RELIKQUARY_PUBLISHER_PASSWORD=changeme`, commented `RELIKQUARY_S3_*`), with no real secret values.

---

## Phase 2: Foundational (blocking prerequisites for all stories)

- [X] T002 Write `deploy/backend.Dockerfile`: multi-stage — build on `eclipse-temurin:21-jdk` running `./gradlew :backend:bootJar` (build context = repo root, copy wrapper/`gradle/`/`backend/`/build files; leverage layer caching), runtime on `eclipse-temurin:21-jre`; create and run as a non-root `relikquary` user; `EXPOSE 8080`; `ENV RELIKQUARY_STORAGE_ROOT=/data`; `HEALTHCHECK` `curl -f http://localhost:8080/actuator/health/readiness`; entrypoint runs the jar.
- [X] T003 Write `deploy/nginx/default.conf.template`: serve the SPA from the web root with `try_files $uri $uri/ /index.html`, and `proxy_pass` `/api` and the Maven repository paths to `${RELIKQUARY_BACKEND}`; suitable for `nginx-unprivileged` envsubst templating on port 8080.
- [X] T004 Add root `build.gradle.kts` `Exec` tasks `dockerBuildBackend`, `dockerBuildFrontend`, `dockerBuildCombined`, and `dockerBuildSplit` (depends on backend+frontend), each running `docker build -f deploy/<file> -t <tag> .` from the repo root and failing with a clear message if the Docker CLI is absent. (Existing root config — kover/sonar — untouched.)

**Checkpoint**: The backend image, the proxy template, and the build entry points exist — compose, the
combined image, and the k8s manifest can now be assembled.

---

## Phase 3: User Story 1 — Run Relikquary as a container (Priority: P1) 🎯 MVP

**Goal**: An operator runs Relikquary (split or combined) and gets a working, persistent, auth-on server with
its UI; a real client can publish and resolve.

**Independent Test**: `docker compose up` → healthy server at :8080, UI at :8081, publish+resolve round-trip
succeeds, artifact survives a restart.

- [X] T005 [US1] Write `deploy/frontend.Dockerfile`: multi-stage — build on `node:22` running `npm ci && npm run build` in `frontend/` with `BASE_PATH` unset (SPA served at `/`); serve on `nginxinc/nginx-unprivileged:stable-alpine`, copying the SPA build output to the web root and `deploy/nginx/default.conf.template` to `/etc/nginx/templates/`; `EXPOSE 8080`; `HEALTHCHECK` on `/`.
- [X] T006 [US1] Write `deploy/combined.Dockerfile`: multi-stage build on `eclipse-temurin:21-jdk` running `./gradlew :backend:bootJar -PbundleFrontend` (reuses the existing `npmBuild` `BASE_PATH=/ui` bundling), runtime on `eclipse-temurin:21-jre`, non-root, `EXPOSE 8080`, readiness `HEALTHCHECK`; single image serving API + `/ui`.
- [X] T007 [US1] Write `deploy/docker-compose.yml`: `backend` service (build `backend.Dockerfile`, named volume `relikquary-store`→`/data`, `relikquary.security.enabled=true` + publisher from `.env`, healthcheck on readiness, `8080:8080`) and `frontend` service (build `frontend.Dockerfile`, `RELIKQUARY_BACKEND=http://backend:8080`, `depends_on: backend service_healthy`, `8081:8080`); include a commented S3 alternative and a commented single-combined-service alternative. No committed secrets (values come from `.env`).

**Checkpoint**: MVP — `docker compose up` yields a working server + UI with persistence and auth.

---

## Phase 4: User Story 2 — Deploy to Kubernetes (Priority: P2)

**Goal**: An operator applies one manifest and gets a probe-gated backend + frontend with config/secret
separation and persistent storage.

**Independent Test**: `kubectl apply` → Deployments Ready (readiness-gated), Service routes; or static
`--dry-run=client`/lint where no cluster exists.

- [X] T008 [US2] Write `deploy/k8s/relikquary.yaml` (multi-document): `ConfigMap` (non-secret env), `Secret` (placeholder `stringData` only), `PersistentVolumeClaim` (RWO, e.g. 10Gi), backend `Deployment` (1 replica; env from ConfigMap+Secret; PVC at `/data`; `livenessProbe` `/actuator/health/liveness` + `readinessProbe` `/actuator/health/readiness`; resource requests/limits; `securityContext` runAsNonRoot + `fsGroup`), backend `Service` (ClusterIP 8080), frontend `Deployment` + `Service` (nginx image, `RELIKQUARY_BACKEND` → backend Service, probes on `/`, resource limits), and a commented `Ingress` example (`/`→frontend, `/api`+repo→backend).

**Checkpoint**: A general, applyable manifest with probes, config/secret split, and persistent storage.

---

## Phase 5: User Story 3 — Choose the storage backend at deploy time (Priority: P3)

**Goal**: Switch filesystem ↔ S3 by configuration only, in both compose and k8s, with no rebuild and no
committed secrets.

**Independent Test**: Same image runs with filesystem (volume/PVC) and with S3 (env/Secret); no rebuild.

- [X] T009 [US3] Ensure the S3 path is documented and switch-ready without committing secrets: in `deploy/.env.example` add commented `RELIKQUARY_S3_*` placeholders; in `deploy/docker-compose.yml` ensure the commented S3 block sets `relikquary.storage.backend=s3` + `RELIKQUARY_S3_*` and drops the volume; in `deploy/k8s/relikquary.yaml` add inline notes to flip `relikquary.storage.backend=s3`, fill the Secret S3 keys, drop the PVC, and raise replicas.

**Checkpoint**: Both deployment paths support both storage backends by configuration alone.

---

## Phase 6: Polish & Cross-Cutting Concerns (verification + docs)

- [X] T010 Add `backend/src/test/kotlin/org/khorum/oss/relikquary/deploy/DeploymentArtifactsTest.kt` (offline, always runs): assert the three Dockerfiles exist and declare a non-root user + a Java-21 base, the backend/combined declare a readiness `HEALTHCHECK`, and the **frontend Dockerfile declares a HEALTHCHECK** (FR-003 full coverage); assert the **nginx template proxies `/api` and repo paths** to `${RELIKQUARY_BACKEND}` and has the SPA `index.html` fallback (FR-004 routing); the compose file enables auth, defines a persistent volume, and a healthcheck; the k8s manifest contains Deployment/Service/ConfigMap/Secret/PVC with probes referencing `/actuator/health/liveness` and `/actuator/health/readiness` and resource limits; and that **no committed deploy artifact contains a real secret value** (only placeholders/`changeme`/`${…}`). Keep it detekt-clean. (FR-003, FR-004, FR-010, FR-014, SC-006)
- [X] T011 [P] Add `deploy/smoke.sh` (Docker-guarded): if `docker` is unavailable, print a skip and exit 0; otherwise build the backend image, run it with a temp volume + a publisher, publish an artifact via `curl -u` PUT and resolve it via GET, assert byte-equality, and tear down. (FR-014, SC-002)
- [X] T012 [P] Write `deploy/README.md` (operator guide): build split vs combined (the Gradle tasks), `docker compose up` with `.env`, `kubectl apply`, switching storage (filesystem ↔ S3), supplying credentials, the non-root volume-permission note, and a build-architecture note (amd64 default; arm64 buildable via `docker build --platform`). (FR-013, arch edge case)
- [X] T013 [P] Add a brief "Run in a container / Kubernetes" section to the top-level `README.md` linking to `deploy/README.md`. (FR-013)
- [X] T014 Run `./gradlew :backend:build` and confirm green: `DeploymentArtifactsTest` passes, detekt zero, Kover holds, strict dependency verification unchanged (no new deps), all existing tests pass — proving no application/config/client regression (FR-012, SC-007). Where Docker is available, also run `bash deploy/smoke.sh`.

---

## Dependencies & Execution Order

- **Setup (T001)** → **Foundational (T002–T004)** precede all stories.
- **US1 (T005–T007)** depends on Foundational → MVP; deliver first.
- **US2 (T008)** depends on Foundational (backend image + nginx template); independent of US1.
- **US3 (T009)** touches the compose + manifest + `.env.example` produced by US1/US2 → after them.
- **Polish (T010–T014)** after the artifacts exist; T014 is the final gate.

## Parallel Opportunities

- T002, T003 (different files) can be written in parallel; T004 references the Dockerfile names only.
- Within US1, T005 and T006 (different Dockerfiles) are parallel; T007 depends on both existing.
- T011, T012, T013 (smoke, deploy README, root README) are `[P]` — different files.
- T010 can be written in parallel with the docs once the artifacts (T002–T009) exist.

## Implementation Strategy

**MVP = Phase 1 + Phase 2 + Phase 3 (US1)**: an operator can `docker compose up` a working, persistent,
auth-on Relikquary with its UI. US2 adds Kubernetes; US3 makes storage switchable; Polish adds the offline
validation test, the guarded smoke, and docs, then the full-build gate.
