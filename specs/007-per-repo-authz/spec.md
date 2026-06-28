# Feature Specification: Per-Repository Authorization

**Feature Branch**: `007-per-repo-authz`

**Created**: 2026-06-28

**Status**: Draft

**Input**: User description: "Add per-repository authorization: govern who may READ (resolve/download +
browse), PUBLISH (upload), and DELETE for each repository, defined in the same static config as users.
Stay backward compatible (global on/off toggle; open reads + PUBLISH-gated writes remain the default),
preserve Maven/Gradle HTTP Basic compatibility (401 vs 403), and apply consistently across the Maven
wire protocol, the browse/manage API, and proxy/group repositories (a group applies the access rules of
whichever member serves the artifact)."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Control who can publish to a repository (Priority: P1)

An operator grants publish access to a repository to specific users so that, for example, only the
platform team can publish to `releases` while another team publishes to their own repo. A user who is
not permitted to publish to a repository is refused even with valid credentials.

**Why this priority**: Scoping publishing per repository is the headline value — it lets multiple teams
share one server without being able to overwrite each other's repositories.

**Independent Test**: Grant user A publish on repo X but not repo Y; A publishes to X (success) and is
forbidden on Y; an unauthenticated publish to a restricted repo is challenged for credentials.

**Acceptance Scenarios**:

1. **Given** a repo restricting publish to user A, **When** A publishes with valid credentials, **Then**
   it succeeds.
2. **Given** the same repo, **When** authenticated user B (not permitted) publishes, **Then** the
   response is `403`.
3. **Given** the same repo, **When** an unauthenticated client publishes, **Then** the response is `401`
   with a Basic auth challenge.

---

### User Story 2 - Private repositories (restrict who can read) (Priority: P1)

An operator marks a repository as readable only by specific users, so a private repository's artifacts
cannot be resolved, downloaded, or browsed by anyone else. Repositories without a read restriction stay
open to everyone, exactly as today.

**Why this priority**: Private artifacts are a core expectation of a repository manager; today every
read is open, so this closes a real gap.

**Independent Test**: Mark repo X read-restricted to user A; A reads an artifact (success); anonymous and
non-permitted reads are refused; an open repo Y still reads anonymously.

**Acceptance Scenarios**:

1. **Given** a read-restricted repo and a permitted user, **When** they resolve or browse it, **Then**
   it succeeds.
2. **Given** a read-restricted repo, **When** an unauthenticated client resolves it, **Then** the
   response is `401`.
3. **Given** a read-restricted repo, **When** a non-permitted authenticated user resolves it, **Then**
   the response is `403`.
4. **Given** a repo with no read restriction, **When** anyone resolves it, **Then** it succeeds
   anonymously (backward compatible).

---

### User Story 3 - Consistent enforcement: delete, browse, groups, and the disable switch (Priority: P2)

Per-repository permissions apply the same way across the delete (manage) action, the browse API, and the
new proxy/group repositories; and disabling security globally still opens everything for local dev.

**Why this priority**: Authorization must be uniform — a rule enforced on the Maven path but not the
browse API (or bypassable through a group) would be a security hole.

**Independent Test**: Confirm delete obeys per-repo delete rules; the browse API obeys read rules;
resolving through a group obeys the serving member's read rule; and with security disabled every action
is open.

**Acceptance Scenarios**:

1. **Given** a repo restricting delete to user A, **When** A deletes a file, **Then** it succeeds; when a
   non-permitted user deletes, **Then** `403` (and `401` if unauthenticated).
2. **Given** a read-restricted repo, **When** its contents are listed via the browse API by a
   non-permitted user, **Then** the read rule denies it consistently with the Maven path.
3. **Given** a group whose member serves an artifact, **When** a user resolves it through the group,
   **Then** access is governed by that member's read policy.
4. **Given** `security.enabled=false`, **When** any action is performed on any repository, **Then** it is
   permitted (local-dev bypass preserved).

### Edge Cases

- **Unknown repo vs unauthorized**: an unknown repository name still returns `404` (existence is not
  secret); a known but protected repository returns `401`/`403`.
- **401 vs 403**: missing/invalid credentials where credentials could grant access → `401` + challenge;
  valid credentials lacking the permission → `403`.
- **Default (no rules)**: a repository that declares no permissions keeps today's behavior — open reads,
  publishing gated by the existing global publish authorization.
- **Proxy/group publish**: proxy and group repos remain read-only (`405` on publish) regardless of authz.
- **Metadata/checksum siblings**: read permission applies uniformly to all files under a repository
  (artifacts, `maven-metadata.xml`, checksums), so a private repo does not leak via a sibling path.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: A repository MAY declare, in the same static configuration as users, which principals may
  perform each action: READ (resolve/download + browse), PUBLISH (upload), and DELETE.
- **FR-002**: A repository that declares no READ restriction MUST remain open (anonymous reads allowed),
  preserving current behavior.
- **FR-003**: A hosted repository that declares no PUBLISH restriction MUST continue to accept publishes
  from any principal holding the existing global publish authorization (today's default behavior).
- **FR-004**: When an action is restricted, the system MUST permit it only for an authenticated,
  permitted principal; an unauthenticated request that credentials could satisfy MUST return `401` with a
  Basic challenge, and an authenticated principal lacking the permission MUST return `403`.
- **FR-005**: READ authorization MUST govern both the Maven wire protocol (`GET`/`HEAD`) and the browse
  API (listing and file details) for that repository, applied uniformly to every file under it.
- **FR-006**: PUBLISH authorization MUST govern `PUT` (upload) to a hosted repository.
- **FR-007**: DELETE authorization MUST govern the manage `DELETE` operation for a repository.
- **FR-008**: Proxy and group repositories remain read-only (publish ⇒ `405`); READ authorization MUST
  gate a proxy's resolution, and a group request MUST be governed by the READ policy of the member that
  serves the artifact.
- **FR-009**: When security is globally disabled, all per-repository rules MUST be ignored and every
  action on every repository MUST be permitted (local-dev bypass).
- **FR-010**: Authorization MUST use the existing HTTP Basic mechanism and configured users (with their
  roles); no new authentication scheme is introduced.
- **FR-011**: An unknown repository name MUST still return `404` (before authorization); an invalid or
  traversal path MUST still return `400`.

### Key Entities

- **Repository Access Policy**: the per-repository permissions for READ, PUBLISH, and DELETE — each a set
  of permitted principals (users and/or roles); absence of a set means the default for that action.
- **Principal**: a configured user (with roles) authenticated via HTTP Basic.
- **Action**: one of READ, PUBLISH, DELETE, evaluated against a repository's access policy.

## Success Criteria *(mandatory)*

- **SC-001**: A user permitted to publish to repo A but not repo B publishes to A successfully and is
  forbidden (`403`) on B; an unauthenticated publish to a restricted repo returns `401`.
- **SC-002**: A read-restricted repository returns `401` to anonymous reads and `403` to non-permitted
  users, while a permitted user reads it successfully; an unrestricted repository still reads anonymously.
- **SC-003**: A deployment with no per-repository rules behaves exactly as before this feature — open
  reads and globally-publish-gated writes (regression preserved).
- **SC-004**: A `DELETE` is allowed only for a delete-permitted user; others receive `403` (or `401`
  unauthenticated), consistently with the read/publish model.
- **SC-005**: Resolving a private artifact through a group is governed by the serving member's read
  policy: a permitted user succeeds, a non-permitted user is denied.
- **SC-006**: With security disabled, all actions on all repositories are permitted.

## Assumptions

- **Grant model**: each repository optionally lists permitted users and/or roles per action; an action
  with no list falls back to its default (READ open; PUBLISH gated by the existing global publish
  authorization; DELETE gated by that same authorization), keeping current behavior. (To confirm in
  `/speckit-clarify`.)
- **Global publish role**: the existing global publish authorization remains valid as the default
  publisher for repositories that do not declare their own publishers; a dedicated admin/superuser role
  is out of scope.
- **Group read semantics**: a group resolves by first match; a member that *has* the artifact but denies
  the requesting user is treated like a non-serving member, so the group continues to the next member
  (permissive union). (To confirm in `/speckit-clarify`.)
- **Static config only**: permissions live in configuration alongside users; a database-backed store and
  any user/permission management UI are out of scope (later specs).
- **Proxy upstream credentials** (feature 006) are server→upstream only and unrelated to this
  client-facing per-repository authorization.
- **Repository existence is not secret**: an unknown repo returns `404` even to anonymous clients; only
  access to a known repository's contents is gated.
