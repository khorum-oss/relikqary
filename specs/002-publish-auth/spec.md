# Feature Specification: Publish Authentication & Authorization

**Feature Branch**: `002-publish-auth`

**Created**: 2026-06-26

**Status**: Draft

**Input**: User description: "Add authentication so only credentialed users can publish; reads stay open. Credentials live in static config. Auth must be switchable off for local development so I don't have to log in every time."

## Clarifications

### Session 2026-06-26

- Q: Read access model? → A: Open read; only publishing (PUT) requires authentication.
- Q: Where do credentials live? → A: Static configuration (username + bcrypt-encoded password +
  roles), not a database (deferred).
- Q: Local development? → A: A single toggle disables auth entirely so local runs need no login.
  Auth is ON by default; disabling is an explicit opt-in.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Only authenticated publishers can publish (Priority: P1)

A maintainer configures publisher credentials. A client that presents valid credentials can publish;
a client with no or wrong credentials is refused, and the stored artifacts are unchanged.

**Why this priority**: Without this, anyone can publish or overwrite artifacts — the core safety gap
this feature closes.

**Independent Test**: With a publisher user configured, run a real Gradle `maven-publish` with
credentials (succeeds) and without/with wrong credentials (refused with 401), and confirm no
unauthorized write occurred.

**Acceptance Scenarios**:

1. **Given** a configured publisher and auth enabled, **When** a client `PUT`s an artifact with valid
   credentials, **Then** it is stored and the response is success (201/200).
2. **Given** auth enabled, **When** a client `PUT`s with no credentials, **Then** the response is 401
   with a `WWW-Authenticate: Basic` challenge and nothing is stored.
3. **Given** auth enabled, **When** a client `PUT`s with wrong credentials, **Then** the response is
   401 and nothing is stored.

---

### User Story 2 - Resolving stays open (Priority: P1)

Consumers continue to resolve and download artifacts without credentials; adding publish auth must
not break anonymous resolution by standard Maven/Gradle clients.

**Why this priority**: Equal to US1 — the publish/resolve round-trip must keep working for readers.

**Independent Test**: With auth enabled and an artifact published, resolve it via real Maven and
Gradle clients with no credentials and confirm success.

**Acceptance Scenarios**:

1. **Given** auth enabled and a published artifact, **When** a client `GET`s it with no credentials,
   **Then** it is served (200).
2. **Given** auth enabled, **When** an unmodified Maven or Gradle client resolves a dependency,
   **Then** resolution succeeds without credentials.

---

### User Story 3 - Disable auth for local development (Priority: P2)

A developer runs Relikquary locally with auth disabled via a single toggle (or the `local` profile)
and can publish without configuring or supplying any credentials.

**Why this priority**: Removes day-to-day friction for local dev; not needed for the security
guarantee itself, hence P2.

**Independent Test**: Start with `relikquary.security.enabled=false`, `PUT` an artifact with no
credentials, and confirm it is stored (201).

**Acceptance Scenarios**:

1. **Given** `relikquary.security.enabled=false`, **When** a client `PUT`s with no credentials,
   **Then** it is stored (201).
2. **Given** the `local` profile is active, **When** the server starts, **Then** auth is disabled and
   local publishing needs no login.

### Edge Cases

- **Authenticated but not a publisher**: a valid user lacking the publish authority `PUT`s → 403.
- **No users configured, auth enabled**: every publish is refused (401) — the repository is locked
  until a publisher is configured.
- **Credentials on read**: presenting credentials on a `GET` is accepted and still serves the file
  (open read is not weakened by sending credentials).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: When auth is enabled, a `PUT` (publish) MUST require an authenticated principal holding
  the publish authority; otherwise the request MUST be refused and no bytes stored.
- **FR-002**: Authentication MUST use HTTP Basic so unmodified Maven (`settings.xml`) and Gradle
  (`credentials { }`) clients can publish without modification.
- **FR-003**: A missing/invalid credential on a protected request MUST yield `401` with a
  `WWW-Authenticate: Basic` challenge; an authenticated principal lacking the publish authority MUST
  yield `403`.
- **FR-004**: `GET`/`HEAD` (resolve) MUST remain accessible without credentials when auth is enabled.
- **FR-005**: Publisher credentials MUST be defined in static configuration as username +
  (encoded) password + roles, with no code change to add/remove users.
- **FR-006**: Passwords MUST be stored using a standard password encoder (bcrypt), supporting encoded
  values in config; plaintext-equivalent (`{noop}`) is permitted only for tests/local.
- **FR-007**: A single configuration flag (`relikquary.security.enabled`, default true) MUST disable
  all authentication when false, permitting publish without credentials (local-dev opt-out).
- **FR-008**: Auth MUST be ON by default; disabling MUST be an explicit configuration choice.
- **FR-009**: Enabling auth MUST NOT alter stored bytes or the repository wire layout (Principle I) —
  it only gates writes.

### Key Entities *(include if feature involves data)*

- **Publisher User**: a configured identity — username, encoded password, roles/authorities —
  authorized to publish.
- **Security Settings**: the `enabled` flag plus the user list, all from configuration.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: With auth enabled, a real Gradle `maven-publish` with valid credentials succeeds, and
  the same publish without credentials fails with 401.
- **SC-002**: With auth enabled and an artifact published, real Maven and Gradle clients resolve it
  with no credentials.
- **SC-003**: With `relikquary.security.enabled=false`, a publish with no credentials succeeds.
- **SC-004**: Default configuration (no overrides) has auth enabled — an unauthenticated publish is
  refused.
- **SC-005**: Adding or removing a publisher requires only a configuration change, no rebuild.

## Assumptions

- HTTP Basic over the deployment's transport is acceptable; TLS termination is a deployment concern,
  out of scope here.
- A single set of global roles suffices (no per-repository permissions yet; single implicit repo).
- Read remains fully open in this slice; authenticated/private read is a later spec.
- Credentials are supplied via configuration/environment; a database-backed user store is deferred.
