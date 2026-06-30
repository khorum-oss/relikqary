# Feature Specification: Frontend Redesign — "Artifact Sanctuary"

**Feature Branch**: `016-frontend-redesign`

**Created**: 2026-06-30

**Status**: Draft

**Input**: User description: "Redesign the Relikquary web UI to match the 'Artifact Sanctuary'
design mockup in `frontend/planning/` (Relikquary UI.dc.html + support.js). Plan the full thing across
three phases (build later). Reskin existing screens; add a real dashboard and a cross-repo artifact
catalog backed by cheap read-only data; and add stateful admin features (API tokens, user management,
publish history/attribution, runtime settings) on top of a new operator-selectable persistence layer."

## Clarifications

### Session 2026-06-30

- Q: How far should the redesign go now? → A: Plan all three phases in full; build later. The phases
  ship independently (a reskin with no backend change; then cheap read-only data; then stateful admin
  features).
- Q: Users & Tokens and runtime Settings need persistent state the backend lacks today. How? → A:
  Introduce real persistence with an **operator-selectable datastore** — an embedded database by
  default, optionally an external database — plus an API-token model. This is an accepted departure
  from the current stateless/config-only design and is confined to Phase 3.
- Q: The mockup's "Repositories" screen is a cross-repo artifact catalog; today's screen lists
  configured repositories. Which? → A: **Both.** Keep the configured-repository list and the
  aggregated artifact catalog, and also keep a raw folder/tree breakdown (the existing real browser).
  A repository drills into either the aggregated catalog or the raw folder view.

## Overview

The redesign replaces the current light, top-nav SvelteKit UI with the "Artifact Sanctuary" design:
a persistent left-sidebar shell (Dashboard · Repositories · Publish · Users & Tokens · Settings), a
topbar with title/search, and a dark "vault" visual language. It is delivered in three independently
shippable phases:

- **Phase 1 — Reskin (no backend change).** Apply the shell and theme; restyle every screen that
  already exists, backed by today's APIs.
- **Phase 2 — Cheap read-only data.** Add a stats summary and a cross-repo artifact catalog + search,
  both sourced from data the server can produce cheaply.
- **Phase 3 — Stateful admin.** Introduce an operator-selectable persistence layer, then build API
  tokens, user management, publish history/attribution, and a runtime settings screen on top of it.

Phase priorities map to story priorities: Phase 1 → **P1**, Phase 2 → **P2**, Phase 3 → **P3**.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Vault-themed app shell & navigation (Priority: P1)

A user opens the web UI and is greeted by the redesigned shell: a left sidebar listing the primary
sections, a topbar showing the current section title, and the dark gold/bronze "vault" theme applied
consistently. Signing in uses the same credentials as today, presented in the new themed login.

**Why this priority**: The shell and theme are the foundation every other screen renders inside; the
visual transformation is mostly delivered here and depends on no backend change.

**Independent Test**: Load the UI, see the themed login, sign in, and navigate between sections via the
sidebar with the active section highlighted and reflected in the URL — without any new backend endpoint.

**Acceptance Scenarios**:

1. **Given** an unauthenticated visitor, **When** the UI loads, **Then** the themed login is shown and
   a successful sign-in reveals the sidebar shell.
2. **Given** a signed-in user, **When** they select a sidebar section, **Then** the topbar title and the
   active sidebar item update and the route changes accordingly.
3. **Given** a signed-in user, **When** they sign out, **Then** the session clears and the themed login
   returns.
4. **Given** any screen, **When** it renders, **Then** it uses the shared theme tokens (no leftover
   light-theme styling).

---

### User Story 2 - Restyled artifact browsing (Priority: P1)

A user browses a configured repository, navigates its folder tree, inspects a file's details, downloads
it, and (when authorized) deletes a file or folder — all in the new theme using the existing browse API.

**Why this priority**: Browsing/downloading is the product's core today; it must survive the reskin
intact and is a standalone slice.

**Independent Test**: With artifacts present, open a repository, drill into a version folder, view a
file's size/checksums/last-modified, download it, and delete a file — each working as before, restyled.

**Acceptance Scenarios**:

1. **Given** configured repositories, **When** the Repositories section loads, **Then** each repository
   is listed with its name, type, and kind (hosted/proxy/group) in the new theme.
2. **Given** a repository, **When** the user opens it in **raw folder view**, **Then** breadcrumbs and a
   folder/file listing (name, size, last-modified) appear and navigation works to any depth.
3. **Given** a stored file, **When** the user selects it, **Then** a details panel shows size,
   last-modified, and available checksums, and download returns the original bytes.
4. **Given** a coordinate's version directory, **When** it is opened, **Then** the coordinate
   (group:artifact:version), any Gradle Module badge, and Gradle/Maven consume snippets are shown.
5. **Given** an authorized user, **When** they delete a file or folder and confirm, **Then** the item is
   removed and the listing refreshes; an unauthorized attempt surfaces a themed forbidden message.

---

### User Story 3 - Restyled publish & consume (Priority: P1)

A publisher opens the Publish section, copies a ready-made Gradle/Maven publish snippet, and uploads an
artifact via a drag-and-drop drop zone (or file picker), using today's upload endpoint.

**Why this priority**: Upload exists today and is part of the mockup's Publish screen; restyling it with
a drop zone is a self-contained Phase-1 slice.

**Independent Test**: Open Publish, copy the publish snippet, drag a file onto the drop zone, and see it
uploaded to the chosen hosted path with success/error feedback — no backend change.

**Acceptance Scenarios**:

1. **Given** the Publish section, **When** it loads, **Then** a copyable publish snippet is shown and the
   copy control confirms the copy.
2. **Given** a hosted repository and target path, **When** the user drops or selects a file, **Then** it
   is uploaded and the result (created/updated/rejected) is reported in the theme.
3. **Given** a non-hosted (read-only) repository, **When** the user attempts upload, **Then** the UI
   prevents it with a clear message.

---

### User Story 4 - Dashboard with live stats (Priority: P2)

A user lands on a Dashboard showing real summary figures — number of repositories, total stored
artifacts, and storage used — instead of placeholder numbers.

**Why this priority**: The Dashboard is the mockup's landing screen; it needs a small read-only stats
feed the server can produce cheaply from data it already tracks.

**Independent Test**: With known stored content, open the Dashboard and verify the repository count,
artifact/object count, and storage-used figures match the actual state.

**Acceptance Scenarios**:

1. **Given** configured repositories and stored artifacts, **When** the Dashboard loads, **Then** it
   shows the repository count, total artifact/object count, and storage-used total.
2. **Given** the stats are momentarily unavailable, **When** the Dashboard loads, **Then** it degrades
   gracefully (themed placeholders/skeleton, not a broken page).
3. **Given** figures derived from periodically-refreshed sources, **When** displayed, **Then** the UI
   does not imply real-time precision beyond what the source guarantees.

---

### User Story 5 - Cross-repo artifact catalog & search (Priority: P2)

A user views an aggregated catalog of artifacts — each row a `group:artifact` with its latest version,
total size, and version count — and uses the topbar search to filter by coordinate. A catalog row opens
the artifact detail; the user can switch a repository between the **catalog view** and the **raw folder
view**.

**Why this priority**: This is the mockup's primary Repositories experience and the search target; it
requires an aggregation the current browse API doesn't provide, but only read-only data.

**Independent Test**: With multiple artifacts/versions published, open the catalog and confirm one row
per `group:artifact` with correct latest version and version count; type a query and see the list filter;
toggle a repository to raw folder view and back.

**Acceptance Scenarios**:

1. **Given** published artifacts across repositories, **When** the catalog loads, **Then** each
   `group:artifact` appears once with its latest version, aggregate size, and version count.
2. **Given** the catalog, **When** the user types a coordinate fragment in search, **Then** the list
   filters to matching `group:artifact` entries.
3. **Given** a catalog row, **When** the user opens it, **Then** the artifact detail shows its versions,
   files, and consume snippets.
4. **Given** a repository, **When** the user toggles view mode, **Then** they can switch between the
   aggregated catalog and the raw folder/tree breakdown of the same repository.

---

### User Story 6 - Operator-selectable persistence foundation (Priority: P3)

An operator configures where Relikquary persists application state: an embedded database by default, or
an external database for shared/HA deployments. All Phase-3 stateful features read and write through this
store.

**Why this priority**: Tokens, user management, publish history, and runtime settings all require durable
state; this is their shared enabler and must exist before them. It is the deliberate departure from the
current stateless design.

**Independent Test**: Start the server with the default embedded store and confirm Phase-3 data persists
across restarts; reconfigure to an external database and confirm the same features work against it.

**Acceptance Scenarios**:

1. **Given** no datastore configuration, **When** the server starts, **Then** it uses the embedded
   default and Phase-3 data survives a restart.
2. **Given** an external database is configured, **When** the server starts, **Then** Phase-3 features
   operate against it without code changes.
3. **Given** an empty/new datastore, **When** the server starts, **Then** required schema is initialized
   automatically.
4. **Given** persistence is unavailable, **When** a Phase-3 screen is used, **Then** the failure is
   reported clearly and Phase-1/2 (stateless) features remain usable.

---

### User Story 7 - API token management (Priority: P3)

A user issues a named API token with a scope (e.g., read or publish), sees it once at creation, and can
list and revoke tokens later. Tokens authenticate Maven/Gradle and UI requests like a password.

**Why this priority**: The mockup's Publish and Users & Tokens screens center on tokens; CI publishing
with revocable credentials (instead of sharing a password) is a top operator need.

**Independent Test**: Create a `publish`-scoped token, use it to publish an artifact, then revoke it and
confirm the same token is rejected afterward.

**Acceptance Scenarios**:

1. **Given** an authorized user, **When** they create a token with a name and scope, **Then** the secret
   is shown exactly once and is thereafter masked.
2. **Given** an issued token, **When** it lists, **Then** it shows name, created, last-used, and scope —
   never the secret again.
3. **Given** a `publish` token, **When** used as credentials by a client, **Then** publish succeeds where
   the scope allows and is denied where it does not.
4. **Given** a token, **When** the user revokes it, **Then** subsequent use is rejected.

---

### User Story 8 - User management (Priority: P3)

An administrator views the user list (username, email, role, last-active) and creates or edits users with
roles, instead of editing static configuration files.

**Why this priority**: The Users tab needs managed accounts; moving from config-only users to managed
accounts is required for the mockup's admin experience.

**Independent Test**: Create a user with a role through the UI, sign in as that user, and confirm their
role-based permissions apply.

**Acceptance Scenarios**:

1. **Given** an administrator, **When** the Users tab loads, **Then** managed users are listed with
   username, email, role, and last-active.
2. **Given** an administrator, **When** they create a user with a role, **Then** the user can authenticate
   and is governed by that role.
3. **Given** a non-administrator, **When** they open Users management, **Then** management actions are
   hidden or denied.
4. **Given** existing static-config users, **When** the system runs, **Then** they continue to function
   during/after migration to managed accounts (no lockout).

---

### User Story 9 - Publish history & attribution (Priority: P3)

The Dashboard shows a "Recent Publishes" feed (artifact, version, by-whom, when), and an artifact's
detail shows who published it and when.

**Why this priority**: Provenance ("published by") and recent activity are prominent in the mockup and
require recording publish events the system doesn't track today.

**Independent Test**: Publish an artifact as a known principal, then see that event at the top of Recent
Publishes and the attribution on the artifact detail.

**Acceptance Scenarios**:

1. **Given** a successful publish by a known principal, **When** the Dashboard loads, **Then** the event
   appears in Recent Publishes with artifact, version, principal, and time.
2. **Given** an artifact, **When** its detail loads, **Then** it shows the publishing principal and
   timestamp where recorded.
3. **Given** an anonymous/unauthenticated publish (where permitted), **When** recorded, **Then**
   attribution is shown as unknown rather than fabricated.

---

### User Story 10 - Runtime settings screen (Priority: P3)

An administrator views and adjusts a bounded set of runtime settings (e.g., display/server identity and
authentication-policy toggles) from a Settings screen, with changes persisted.

**Why this priority**: Settings is the last mockup screen; only operationally-safe settings are exposed,
to avoid turning every static config knob into a live one.

**Independent Test**: Change an exposed setting, save, reload, and confirm the new value persists and
takes effect.

**Acceptance Scenarios**:

1. **Given** an administrator, **When** the Settings screen loads, **Then** the editable settings show
   their current values.
2. **Given** an edited setting, **When** saved, **Then** the value persists across reloads/restarts and
   the effect is observable.
3. **Given** a non-administrator, **When** they open Settings, **Then** it is read-only or denied.
4. **Given** an invalid value, **When** the user attempts to save, **Then** it is rejected with a clear
   message and the prior value is retained.

---

### Edge Cases

- **Large catalogs/listings**: very many artifacts or versions must not break the catalog or folder view
  (the UI must page, virtualize, or otherwise bound what it renders, and disclose when results are
  truncated).
- **Group repositories**: a group repository has no own storage to browse; the UI must guide the user to
  its members rather than showing an empty raw view.
- **Proxy repositories**: the catalog reflects cached content only; the UI must not imply it lists the
  entire upstream universe.
- **Session expiry mid-action**: an expired/invalid session during browse, upload, or an admin action
  must reopen login and resume, not lose the user's place silently.
- **Token shown once**: if the user navigates away before copying a new token secret, it cannot be
  retrieved — the UI must warn before that point.
- **Persistence outage**: Phase-3 screens must fail clearly while Phase-1/2 stateless features keep
  working.
- **Migration coexistence**: static-config users/credentials and managed users must coexist without
  duplicate-identity confusion or lockout.
- **Empty states**: no repositories, no artifacts, no tokens, no users, no recent publishes each need a
  themed empty state, not a blank panel.

## Requirements *(mandatory)*

### Functional Requirements

**Phase 1 — Reskin (no backend change)**

- **FR-001**: The UI MUST present a persistent left-sidebar shell with sections Dashboard, Repositories,
  Publish, Users & Tokens, and Settings, plus a topbar showing the active section title.
- **FR-002**: The UI MUST apply a single shared visual theme (the "vault" palette, heading and monospace
  type treatments) consistently across all screens via reusable tokens.
- **FR-003**: The UI MUST retain existing authentication behavior (same credentials, browser login form
  rather than the native auth dialog) presented in the new theme, including sign-out.
- **FR-004**: The UI MUST keep all current browse capabilities — list configured repositories; navigate a
  repository's folders/files with breadcrumbs; view file details (size, last-modified, checksums);
  download original bytes; and delete a file/folder when authorized.
- **FR-005**: The UI MUST keep coordinate detection and consume snippets (Gradle Kotlin/Groovy, Maven)
  and the Gradle Module metadata view for recognized coordinates.
- **FR-006**: The Publish screen MUST provide a copyable publish snippet and a drag-and-drop upload (with
  file-picker fallback) targeting today's upload endpoint, with created/updated/rejected feedback.
- **FR-007**: The redesign MUST NOT change the Maven/Gradle publish/resolve protocol, per-repository
  authorization, hosted/proxy/group semantics, or the standalone-or-bundled deployment model.

**Phase 2 — Cheap read-only data**

- **FR-008**: The system MUST expose a read-only summary of repository count, total stored
  artifact/object count, and storage used, suitable for the Dashboard.
- **FR-009**: The system MUST expose an aggregated artifact catalog where each `group:artifact` carries
  its latest version, aggregate size, and version count.
- **FR-010**: The UI MUST present the catalog as the Repositories default view and allow toggling a
  repository to the raw folder/tree view (and back).
- **FR-011**: The UI MUST provide topbar search that filters the catalog by coordinate fragment.
- **FR-012**: Catalog and summary data MUST be derived from existing stored state without changing how
  artifacts are stored or resolved.

**Phase 3 — Stateful admin (depends on persistence)**

- **FR-013**: The system MUST provide an operator-selectable persistence backend — an embedded default
  and an optional external database — initializing required schema automatically.
- **FR-014**: The system MUST support API tokens: create (named, scoped), list (without re-revealing the
  secret), and revoke; tokens authenticate clients per their scope.
- **FR-015**: A newly created token's secret MUST be revealed exactly once and never retrievable
  afterward.
- **FR-016**: The system MUST support managed user accounts (list, create, assign role) as the source for
  the Users screen, coexisting with existing static-config users without lockout.
- **FR-017**: The system MUST record publish events (artifact, version, principal, timestamp) and expose
  a recent-publishes feed and per-artifact attribution.
- **FR-018**: The system MUST provide a bounded set of runtime-adjustable settings, persisted and
  restricted to administrators, with validation and clear errors.
- **FR-019**: Phase-3 administrative actions (token/user/settings management) MUST be restricted to
  appropriately authorized users.
- **FR-020**: When persistence is unavailable, Phase-3 screens MUST report the failure clearly while
  Phase-1/2 features remain functional.

### Key Entities *(Phase 3)*

- **API Token**: a named, scoped credential belonging to a user/principal; attributes include name,
  scope(s), created time, last-used time, revoked state, and a one-time-revealed secret (stored only in
  verifiable, non-recoverable form).
- **User Account**: a managed identity with username, optional email, role(s), and last-active time;
  authenticates to the UI and to Maven/Gradle.
- **Publish Event**: a record of one publish — coordinate (group:artifact:version), file(s), principal,
  and timestamp — powering recent activity and attribution.
- **Setting**: a persisted, administrator-editable runtime configuration value with a known key, current
  value, and validation rules.
- **Catalog Entry** *(Phase 2, derived not stored)*: an aggregation keyed by `group:artifact` carrying
  latest version, version count, and aggregate size.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of screens present in the mockup (Login, Dashboard, Repositories, Artifact Detail,
  Publish, Users & Tokens, Settings) exist in the redesigned UI by the end of Phase 3.
- **SC-002**: Phase 1 ships with **zero** changes to backend endpoints or the publish/resolve protocol.
- **SC-003**: Every capability available in the current UI (browse, file details, download, delete,
  upload, snippets, module view) remains available after the reskin, verified by the existing UI tests
  passing against the redesigned screens.
- **SC-004**: From the Dashboard, a user can read the live repository count, artifact count, and storage
  used, each matching the actual stored state at load time.
- **SC-005**: In the catalog, each `group:artifact` appears exactly once with a correct latest version
  and version count, and a coordinate search returns the matching entries.
- **SC-006**: An operator can run the product on the embedded datastore with no extra setup, and switch
  to an external database via configuration alone (no code change).
- **SC-007**: A user can issue a scoped token, authenticate a client with it, and revoke it such that
  the revoked token is rejected on its next use.
- **SC-008**: After a publish by a known principal, that publish appears in Recent Publishes and as the
  artifact's attribution.
- **SC-009**: A first-time visitor can identify the active section and navigate between all sidebar
  destinations without instruction.

## Assumptions

- **Phased delivery**: Phases 1→2→3 are built and shipped in order; each is independently valuable. This
  spec plans all three; implementation timing is deferred ("build later").
- **Reuse of existing APIs in Phase 1**: the reskin targets the current browse/upload/download/delete
  endpoints and the existing Basic-auth session model unchanged.
- **Stats source (Phase 2)**: summary figures derive from data the server already tracks about stored
  content; near-real-time (periodically refreshed) precision is acceptable for the Dashboard.
- **Catalog source (Phase 2)**: the catalog is computed from stored artifacts; for proxy repositories it
  reflects cached content only.
- **Persistence is Phase-3-only (deliberate departure)**: the system stays stateless for Phases 1–2;
  durable state is introduced only for Phase-3 features, behind an operator-selectable backend (embedded
  default, optional external database).
- **Settings scope is bounded**: only operationally-safe settings become runtime-editable; storage
  backend, repository topology, and security-critical infrastructure remain static configuration.
- **Managed users coexist with config users**: introducing managed accounts does not remove or override
  existing static-config users; both authenticate during and after the transition.
- **Design source of truth**: `frontend/planning/Relikquary UI.dc.html` (+ `support.js`) defines the
  target look, layout, and screen inventory; it is a mockup with placeholder data, not a literal data
  contract.
- **Deployment unchanged**: the SvelteKit app keeps running standalone or bundled into the backend as a
  single deployable.
