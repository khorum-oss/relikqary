# Feature Specification: Deployment Packaging for Self-Hosting

**Feature Branch**: `claude/spec-013-deployment-packaging`

**Created**: 2026-06-29

**Status**: Draft

**Input**: User description: "Deployment packaging for self-hosting Relikquary. Today Relikquary ships only as a Spring Boot fat jar with no container image or deployment manifests, so operators must hand-roll how to run it. Add first-class, documented deployment artifacts: (1) a container image (multi-stage, slim JRE 21, non-root, healthcheck wired to liveness/readiness probes, optional bundled SvelteKit UI under /ui); (2) a docker-compose file with a persistent volume, env config, healthcheck, auth on, optional S3 path; (3) a general Kubernetes manifest (Deployment + Service, probes, ConfigMap, Secret placeholder, PVC with documented S3 alternative, resource requests/limits). Helm out of scope unless trivial. Keep secrets out of source control, work with both storage backends, leave existing app/build/config/client contract unchanged, document for operators. Verify by building the image and a real publish/resolve through the running container where a runtime is available, else static validation + manual smoke."

## Clarifications

### Session 2026-06-29

- Q: Should the recommended image bundle the web UI by default, or default to API-only? → A: Ship **two separate images** — a backend (API) image and a frontend (UI) image — as the primary packaging, **and** offer a **combined** single-image option (UI bundled into the backend under `/ui`) when explicitly triggered. A simple build task selects combined vs. split.
- Q: Should this feature add CI that builds/publishes the image to a registry, or ship artifacts only? → A: **Artifacts only.** Add the Dockerfiles, compose, and manifest plus a local/guarded test; do **not** publish to any registry and do **not** add a CI publish/push job.
- Q: What storage should the shipped Kubernetes manifest default to? → A: **PVC filesystem, single replica** (ReadWriteOnce); S3 documented as the multi-replica alternative via the Secret.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Run Relikquary as a container (Priority: P1)

An operator wants Relikquary running without installing a JDK or hand-crafting a launch command. They build (or pull) the container image and start it — directly or via the provided compose file — and get a working server: the HTTP API responds, artifacts persist across restarts, and authentication is on by default. A standard Maven/Gradle client can publish and resolve against the container.

**Why this priority**: A runnable container is the foundation of every other deployment path (compose and Kubernetes both run the same image). Delivering just this makes Relikquary self-hostable reproducibly — the core value of the feature.

**Independent Test**: Build the image, run it with a persistent volume and a configured publisher, publish an artifact with a real client, restart the container, and resolve the same artifact back — proving persistence and a working server in a container.

**Acceptance Scenarios**:

1. **Given** the built image, **When** the operator runs it with a storage volume and a configured user, **Then** the server starts, its health endpoint reports healthy, and the API is reachable on the expected port.
2. **Given** a running container, **When** a real Gradle/Maven client publishes an artifact (with credentials) and then resolves it, **Then** the round-trip succeeds.
3. **Given** an artifact was published, **When** the container is restarted with the same volume, **Then** the artifact is still resolvable (data survived the restart).
4. **Given** the compose file, **When** the operator runs the single up command, **Then** a working server comes up with auth on and storage persisted, with no manual editing required beyond providing credentials.

---

### User Story 2 - Deploy to Kubernetes (Priority: P2)

An operator running Kubernetes applies the provided manifest as a starting point and gets a Deployment and Service with health probes, configuration separated from secrets, and persistent storage — ready to adapt to their cluster (ingress, storage class, replicas).

**Why this priority**: Kubernetes is the most common production target for self-hosted infrastructure, but it builds on the image from US1. It is high-value yet secondary to having a runnable image at all.

**Independent Test**: Apply the manifest to a cluster (or validate it statically where no cluster is available); confirm the Deployment becomes Ready with its liveness/readiness probes passing and the Service routes to it.

**Acceptance Scenarios**:

1. **Given** the manifest, **When** it is applied, **Then** it creates a Deployment, a Service, a ConfigMap for non-secret configuration, a Secret placeholder for credentials, and a persistent volume claim — with resource requests/limits set.
2. **Given** the running Deployment, **When** Kubernetes runs the configured liveness and readiness probes, **Then** they target the application's probe endpoints and the pod becomes Ready only when the server can serve requests.
3. **Given** the manifest, **When** an operator inspects it, **Then** no real credentials are present — only placeholders sourced from a Secret — and switching to S3 storage is documented via the same Secret/Config mechanism.

---

### User Story 3 - Choose the storage backend at deploy time (Priority: P3)

An operator selects filesystem or S3-compatible storage purely through configuration (environment/secret), without rebuilding the image, in both the compose and Kubernetes paths.

**Why this priority**: The product already supports both backends; packaging must not lock operators into one. It is a configuration concern layered on US1/US2 rather than a new capability.

**Independent Test**: Run the same image once with filesystem config (volume) and once with S3 config (endpoint/bucket/credentials via secret/env) and confirm each starts and serves; confirm no image rebuild is needed to switch.

**Acceptance Scenarios**:

1. **Given** the same image, **When** configured for filesystem storage with a mounted volume, **Then** artifacts persist on that volume.
2. **Given** the same image, **When** configured for S3 storage via secret/env, **Then** the server uses object storage and the compose/manifest document this path without committing any credentials.

---

### Edge Cases

- **Volume permissions for a non-root process**: The container runs as a non-root user; the persistent storage location must be writable by that user, or the server fails to store artifacts. The packaging must make the storage path owned/writable by the runtime user (documented for host-mounted volumes).
- **Startup vs. readiness**: While the server is still starting, readiness must report not-ready so traffic/orchestration waits; liveness must not kill a slow-but-healthy startup. Probe timings must reflect realistic startup time.
- **Frontend bundling toggle**: The image can be built with or without the bundled UI; when built without it, `/ui` is simply absent while the API is unaffected. The default bundling choice is defined in Assumptions/Clarifications.
- **Missing or empty credentials**: With auth on and no users configured, publishing is locked; the packaging defaults must make it obvious how to supply a publisher (and must not ship a real default password).
- **S3 selected but credentials absent**: Starting with S3 configured but no credentials must fail clearly (diagnosable), not silently fall back to filesystem.
- **Image architecture**: Operators may run on amd64 or arm64; the base image choice should not preclude common architectures (documented if single-arch).
- **No container runtime in CI/sandbox**: Where no container runtime is available, verification falls back to static validation of the artifacts plus a documented manual smoke — the build must not hard-fail for lack of a daemon.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The project MUST provide a **backend (API) container image** built from source in a multi-stage build that runs the server on a slim Java 21 runtime base.
- **FR-001a**: The project MUST provide a **frontend (UI) container image** that serves the web UI as its own container, suitable for running the UI separately from the API.
- **FR-001b**: The project MUST provide a **combined single-image option** that serves both the API and the UI (UI under `/ui`) from one container, selectable via an explicit build trigger; a simple build task MUST let an operator choose the combined build vs. the split (separate) images.
- **FR-002**: Each container MUST run as a non-root user and expose its HTTP port.
- **FR-003**: The backend (and combined) container MUST define a healthcheck wired to the application's existing liveness/readiness probe endpoints; the frontend image MUST define a healthcheck appropriate to serving the UI.
- **FR-004**: When the UI and API run as separate images, requests MUST reach the API correctly (the UI's API/repository calls are routed to the backend, e.g. via a documented reverse-proxy/ingress or same-origin routing); the combined image serves both on one origin with the UI under `/ui`.
- **FR-005**: The containers MUST be configurable entirely through environment variables and/or mounted configuration (storage location/backend, credentials, upstream URLs), with no image rebuild required to change configuration.
- **FR-006**: The project MUST provide a docker-compose definition that brings up a working deployment — the backend with a persistent volume for filesystem storage, authentication enabled by default, healthchecks, and the UI served (via the frontend service or the combined image) — such that a single up command yields a working server reachable with its UI.
- **FR-007**: The compose definition MUST document/provide an optional path for the S3-compatible storage backend without committing any credentials.
- **FR-008**: The project MUST provide a general Kubernetes manifest with a backend Deployment + Service (liveness/readiness probes pointed at the application's probe endpoints, resource requests/limits set) and a frontend Deployment + Service for the UI; the combined single-image deployment MUST be documented as an alternative. The shipped default MUST use filesystem storage on a PersistentVolumeClaim with a single backend replica.
- **FR-009**: The Kubernetes manifest MUST separate non-secret configuration (ConfigMap) from credentials (Secret), with the Secret containing only placeholders, and MUST provide persistent storage via a PersistentVolumeClaim (ReadWriteOnce) for the default filesystem backend, with the S3 alternative — enabling multiple replicas — documented via the same Config/Secret mechanism.
- **FR-010**: All deployment artifacts MUST keep secrets out of source control — using environment variables, placeholders, and Secret references, never committed credential values.
- **FR-011**: All deployment artifacts MUST support both storage backends (filesystem and S3-compatible) through configuration alone.
- **FR-012**: The feature MUST NOT change the existing application code, build, configuration surface, or the Maven/Gradle client contract; deployment packaging is additive.
- **FR-013**: The deployment artifacts and their usage (build, run, compose up, apply, switch storage, supply credentials) MUST be documented for operators.
- **FR-014**: Verification MUST exercise a real publish/resolve through the running container where a container runtime is available (a local, guarded test), and MUST fall back to static validation of the deployment artifacts plus a documented manual smoke where no runtime is available — consistent with how the project already guards runtime-dependent tests. This feature MUST NOT add a registry publish/push step or any CI job that publishes the image anywhere.

### Key Entities *(include if feature involves data)*

- **Backend (API) image**: A reproducible, runnable packaging of the server (multi-stage build → slim JRE 21 runtime), non-root, health-checked against the liveness/readiness probes.
- **Frontend (UI) image**: A container that serves the web UI on its own, health-checked, for running the UI separately from the API.
- **Combined image (option)**: A single image serving both API and UI (UI under `/ui`), produced when the combined build is explicitly triggered.
- **Build task**: A simple, documented build entry point that selects the combined build vs. the split (separate) images.
- **Compose definition**: A single-command deployment bringing up the backend (persisted storage, auth-on, healthchecks) and the UI; documents the S3 alternative.
- **Kubernetes manifest**: A starting-point cluster deployment — backend Deployment + Service (PVC, single replica) and frontend Deployment + Service, with ConfigMap, Secret (placeholders), probes, resource limits, and a documented combined-image alternative.
- **Configuration surface (existing)**: The environment/config keys the server already understands (storage backend & location, credentials, upstream URLs); packaging wires these, it does not add new ones.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An operator can go from a clean checkout to a running, healthy server in a container with a single documented command (plus supplying credentials), without installing a JDK.
- **SC-002**: A real Maven/Gradle client publishes and resolves an artifact through the running container successfully (byte-faithful round-trip).
- **SC-003**: Artifacts published to the container survive a container restart when using the persistent volume.
- **SC-004**: Applying the Kubernetes manifest (or validating it where no cluster exists) yields a Deployment that becomes Ready only when the server is actually serving, with probes targeting the application's probe endpoints.
- **SC-005**: The same image runs against either storage backend selected purely by configuration, with no rebuild.
- **SC-006**: No deployment artifact contains a real secret; a reviewer can confirm only placeholders/Secret references are committed.
- **SC-007**: All existing application behavior and tests remain unchanged after the feature is added (the build stays green; no application/config/client-contract change).

## Assumptions

- **Split images, with a combined option** (per Clarifications): the primary packaging is two images — backend (API) and frontend (UI) — plus a combined single-image build triggered explicitly; a simple build task selects combined vs. split. The combined build reuses the existing frontend-bundling toggle (UI under `/ui`).
- **Image build source**: Images are built from this repository via the existing build (`bootJar`, plus the frontend build/bundle), targeting Java 21 (the project's toolchain). No new application dependency is introduced.
- **Artifacts only, no registry** (per Clarifications): this feature delivers the Dockerfiles, compose, and manifest plus documentation and a local guarded test; it does not publish to any registry and adds no CI publish/push job.
- **Kubernetes default storage** (per Clarifications): filesystem on a PersistentVolumeClaim (single replica, ReadWriteOnce), with S3 documented as the multi-replica-friendly alternative.
- **Split-deployment routing**: when UI and API run separately, a documented reverse proxy / ingress (or same-origin routing) sends API and repository paths to the backend and UI paths to the frontend; the combined image sidesteps this by serving both on one origin.
- **Compose default**: Auth on, filesystem storage on a named/persistent volume, one publisher supplied via environment at run time; the Maven Central and Gradle Plugin Portal proxies use their shipped defaults.
- **No container runtime in this environment**: Like the project's Docker-gated tests (e.g. MinIO Testcontainers), container-runtime verification auto-skips when no runtime is present, with static validation + a documented manual smoke as the always-available fallback. This is an explicit, justified testing deviation, not a silent skip.
- **Helm**: Out of scope unless trivially derivable from the manifest; if not trivial, it is explicitly deferred.
- **Port and probes**: The server's existing HTTP port and existing liveness/readiness probe endpoints are reused as-is; packaging does not introduce new endpoints.
