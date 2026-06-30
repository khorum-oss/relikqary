# Implementation Plan: Deployment Packaging for Self-Hosting

**Branch**: `claude/spec-013-deployment-packaging` | **Date**: 2026-06-29 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/013-deployment-packaging/spec.md`

## Summary

Add operator-facing deployment artifacts without touching application code, build behavior, config surface,
or the client contract. Deliver under a new top-level `deploy/` directory: a **backend (API)** image
(multi-stage → slim JRE 21, non-root, healthcheck on the actuator readiness probe), a **frontend (UI)**
image (build the SvelteKit `adapter-static` SPA, serve it with non-root nginx that reverse-proxies `/api`
and Maven repo paths to the backend — the production analogue of the existing vite dev proxy), and a
**combined** single-image option (backend built with the existing `-PbundleFrontend` toggle so it serves
the UI under `/ui`). A small set of **root Gradle Exec tasks** wraps `docker build` to select combined vs.
split. A **docker-compose** file brings up backend (persistent volume, auth on) + frontend with healthchecks
and a documented S3 path. A **Kubernetes manifest** ships backend Deployment+Service (PVC filesystem, single
replica, probes, resource limits) + frontend Deployment+Service, ConfigMap/Secret(placeholders), and a
documented S3/multi-replica + combined-image alternative. Verification is an always-on, offline static
validation test of the artifacts (structure + no committed secrets + probes wired to actuator) plus a
documented, Docker-guarded local smoke (build image → run → real publish/resolve). Artifacts only — **no
registry push, no CI publish job**.

## Technical Context

**Language/Version**: Kotlin 2.3.21 / JDK 21 (unchanged). New artifacts are Dockerfiles, YAML, an nginx
config, and a small Kotlin validation test; plus root Gradle `Exec` tasks.

**Primary Dependencies**: **No new application or build dependency.** Images use public base images
(`eclipse-temurin:21-jdk` build / `:21-jre` runtime; `node:22` build; `nginxinc/nginx-unprivileged` serve),
pinned. The validation test uses the existing JUnit 5 + Kotlin test stack only.

**Storage**: Unchanged. Packaging wires the existing `RELIKQUARY_STORAGE_ROOT` (filesystem, default
backend) to a volume/PVC, and documents `relikquary.storage.backend=s3` + `RELIKQUARY_S3_*` via env/Secret.

**Testing**: An offline `DeploymentArtifactsTest` (always runs) asserting artifact structure, non-root,
JRE 21, probes pointed at `/actuator/health/{liveness,readiness}`, and absence of committed secret values.
A Docker-guarded `deploy/smoke.sh` (documented, run manually / where a runtime exists) builds the backend
image, runs it, and performs a real publish + resolve round-trip via a real client/curl.

**Target Platform**: Linux containers (amd64; arm64 buildable — documented). Orchestrators: Docker /
Docker Compose and Kubernetes.

**Project Type**: Web service + separable SPA (existing `backend/` + `frontend/`); this feature adds a
sibling `deploy/` tree and root build tasks, no module source changes.

**Performance Goals**: Small runtime images (JRE-only backend; static-served UI). Build-stage layer caching
(dependencies before sources) for fast rebuilds. Cache hits unaffected — no app change.

**Constraints**: Existing app/build/config/client contract unchanged (FR-012); strict Gradle dependency
verification untouched (no new deps); detekt zero + Kover hold for the new Kotlin test; **secrets never
committed** — Secret/`.env` placeholders only; non-root containers; works on both storage backends.

**Scale/Scope**: New `deploy/` dir (3 Dockerfiles, nginx conf + template, `docker-compose.yml`, `.env.example`,
`k8s/relikquary.yaml`, `smoke.sh`, `README.md`), root Gradle Exec tasks, one Kotlin validation test, and
top-level README docs. No production Kotlin/TS source touched.

## Constitution Check

*GATE: re-checked after Phase 1 design — PASS (with one documented testing deviation).*

- **I. Repository Contract & Client Compatibility** — PASS. No change to served layout, resolution, publish
  acceptance, HTTP API, or the configuration surface. Packaging consumes the existing env/config keys and
  the existing actuator probe endpoints; it adds nothing clients depend on. No version bump triggered (a new
  `deploy/` tree is additive tooling).
- **II. Test-First & Integration-Verified** — PASS with documented deviation. App behavior is already
  round-trip-tested; this feature ships *packaging*, which cannot be fully integration-tested without a
  container runtime (absent here, like the Docker-gated MinIO test in feature 003). Mitigation: an always-on
  offline `DeploymentArtifactsTest` locks the artifacts' critical invariants, and a Docker-guarded
  `smoke.sh` performs a real publish/resolve through the running image where a runtime exists. This explicit
  deviation mirrors the project's existing Docker-availability guarding — not a silent skip.
- **III. Quality Gates** — PASS. The new Kotlin test obeys detekt/Kover; no new dependency → strict
  dependency verification and `verification-metadata.xml` untouched. Base images are pinned (supply chain).
- **IV. Supply-Chain Integrity & Faithful Storage** — PASS, reinforced. No secret values are committed
  (Secret/`.env` placeholders, env-sourced). Pinned base images bound the trust surface. Storage faithfulness
  is unchanged — the same app writes the same bytes; packaging only relocates *where* the volume lives.

No unjustified violations → Complexity Tracking not required.

## Project Structure

### Documentation (this feature)

```text
specs/013-deployment-packaging/
├── plan.md              # This file
├── research.md          # Phase 0 decisions (D1–D8)
├── data-model.md        # Artifact inventory + config/secret key mapping (no app schema change)
├── quickstart.md        # Operator validation scenarios
├── contracts/
│   └── deployment.md    # Image/compose/manifest contract: ports, env, probes, volumes, secrets
├── checklists/
│   └── requirements.md  # Spec quality checklist (specify + clarify)
└── tasks.md             # Phase 2 output (/speckit-tasks)
```

### Source Code (repository root)

```text
deploy/
├── backend.Dockerfile          # multi-stage: temurin-jdk build → temurin-jre runtime, non-root, HEALTHCHECK
├── frontend.Dockerfile         # node build (adapter-static SPA) → nginx-unprivileged serve + API proxy
├── combined.Dockerfile         # backend built with -PbundleFrontend (UI under /ui), single image
├── nginx/
│   ├── default.conf.template   # SPA try_files fallback + proxy_pass /api & repo paths to ${RELIKQUARY_BACKEND}
│   └── README.md               # routing notes
├── docker-compose.yml          # backend (volume, auth on, healthcheck) + frontend; S3 path commented
├── .env.example                # documented env placeholders (NO real secrets)
├── k8s/
│   └── relikquary.yaml         # ConfigMap + Secret(placeholders) + PVC + backend Deploy/Svc + frontend Deploy/Svc (+ commented Ingress example, S3 notes); no Namespace (apply into the operator's chosen namespace)
├── smoke.sh                    # Docker-guarded: build backend image, run, real publish/resolve round-trip
└── README.md                   # operator guide: build (split vs combined), compose up, kubectl apply, switch storage, supply creds

build.gradle.kts (root)         # ADD Exec tasks: dockerBuildBackend / dockerBuildFrontend / dockerBuildCombined (+ dockerBuildSplit aggregate)

backend/src/test/kotlin/org/khorum/oss/relikquary/deploy/
└── DeploymentArtifactsTest.kt  # offline: structure, non-root, JRE21, probes→actuator, no committed secrets

README.md                       # top-level: link to deploy/README.md, brief "run in a container / k8s" section
```

**Structure Decision**: A new top-level `deploy/` tree keeps all operator artifacts together and separate
from application source, reflecting that this feature changes *how Relikquary is shipped*, not what it does.
The only build wiring is additive root Gradle Exec tasks that shell out to `docker build` (no new
dependency). The single Kotlin test lives in the backend test module so it runs in the normal gate.

## Complexity Tracking

No constitutional violations requiring justification. The one deviation (packaging can't be fully
integration-tested without a container runtime) is handled by an always-on static-validation test plus a
Docker-guarded smoke, consistent with the project's existing Docker-availability guarding — recorded under
Constitution Check II, not a violation.
