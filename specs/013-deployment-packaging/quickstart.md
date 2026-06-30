# Quickstart & Validation: Deployment Packaging

Operator-facing run/validation guide. Implementation details live in `tasks.md`; this proves the artifacts
work. Commands assume the repo root and the project's Gradle wrapper. A container runtime is needed only for
Scenarios B–E; Scenario A always runs.

## Prerequisites

- For image/compose/k8s scenarios: a Docker (or compatible) runtime; for k8s, a cluster + `kubectl`.
- Copy `deploy/.env.example` to `deploy/.env` and set a publisher password before `compose up`.

## Scenario A — Static artifact validation (offline, always runs)

Proves FR-010, the probe wiring, and structural invariants (SC-006).

```bash
./gradlew :backend:test --tests '*DeploymentArtifactsTest*'
```
**Expected**: passes — Dockerfiles are non-root + JRE 21 with a readiness HEALTHCHECK; the compose file has
auth on, a persistent volume, and a healthcheck; the k8s manifest has Deployment/Service/ConfigMap/Secret/
PVC with probes pointing at `/actuator/health/{liveness,readiness}` and resource limits; **no committed
artifact contains a real secret**.

## Scenario B — Build the images (split and combined)

Proves FR-001/001a/001b and the build-task selection.

```bash
./gradlew dockerBuildSplit       # backend + frontend images
./gradlew dockerBuildCombined    # single API+UI image
```
**Expected**: `relikquary-backend:local`, `relikquary-frontend:local`, and `relikquary:local` build
successfully. (Tasks fail with a clear message if Docker is absent.)

## Scenario C — Run via docker-compose (split, filesystem, auth on)

Proves User Story 1, SC-001/002/003.

```bash
cp deploy/.env.example deploy/.env   # set RELIKQUARY_PUBLISHER_PASSWORD
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d
```
1. Health: `curl -f http://localhost:8080/actuator/health/readiness` → healthy; UI at `http://localhost:8081`.
2. Publish + resolve a real artifact (Gradle `maven-publish` with credentials, or `curl -u` PUT then GET) at
   `http://localhost:8080/releases/...` → round-trip succeeds, byte-identical.
3. `docker compose restart backend` → the artifact is still resolvable (volume persisted).

## Scenario D — Storage backend switch (filesystem ↔ S3)

Proves User Story 3, SC-005.

1. Filesystem (default): artifacts persist on the `relikquary-store` volume (Scenario C).
2. S3: uncomment the S3 block (set `relikquary.storage.backend=s3` + `RELIKQUARY_S3_*` in `.env`), drop the
   volume, `up` again → server uses object storage. **Same image, no rebuild.**

## Scenario E — Apply to Kubernetes

Proves User Story 2, SC-004.

```bash
# Set real values in the Secret first (or via your secrets tooling), then:
kubectl apply -f deploy/k8s/relikquary.yaml
kubectl rollout status deploy/relikquary-backend
```
**Expected**: backend + frontend Deployments become Ready; the pod is Ready only once the readiness probe
passes; the Service routes to it. Where no cluster is available, validate statically:
`kubectl apply --dry-run=client -f deploy/k8s/relikquary.yaml` (or a YAML lint) — documented as the fallback.

## Scenario F — Guarded local smoke (where Docker exists)

```bash
bash deploy/smoke.sh
```
**Expected (Docker present)**: builds the backend image, runs it on a temp volume with a publisher, performs
a real publish + resolve, asserts byte-equality, tears down. **Expected (no Docker)**: prints a skip message
and exits 0.

## Scenario G — No regressions

```bash
./gradlew :backend:build
```
**Expected**: green — detekt zero, Kover holds, strict dependency verification unchanged (no new deps), all
existing tests pass; no application/config/client change.
