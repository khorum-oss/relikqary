# Deferred Follow-ups (from the publish-and-resolve MVP)

Work intentionally left out of the 001 MVP, captured here for future specs. None of these block the
MVP; each is a candidate for its own `/speckit-specify`.

- **Server-side `maven-metadata.xml` synthesis** — the MVP stores and serves the client-uploaded
  `maven-metadata.xml` (research.md §3). Synthesising/merging version listings server-side becomes
  necessary for independent/concurrent publishers and richer SNAPSHOT handling.
- **Configurable strict checksum validation (FR-009a)** — the MVP stores checksum sidecars as
  received. A future mode could reject checksum-mismatched uploads, togglable globally and per
  coordinate.
- **Remote/object storage backends** — add an S3 / DigitalOcean Spaces `ArtifactStorage`
  implementation behind the existing abstraction; verify with Testcontainers (e.g. MinIO), per the
  constitution's integration-testing requirement.
- **Authentication & authorization** — publishing and reading are open in the MVP; add credentialed
  publish and optional read auth.
- **Gradle Module Metadata (`.module`)** — feature-variant aware handling beyond byte passthrough.
- **Multiple named repositories** — repository management, hosted vs proxy repos, release/snapshot
  separation.
- **Dependency signature verification** — the build currently enforces SHA-256 checksum verification;
  layering GPG signature verification (with a curated keyring) is a hardening step.
- **Relikqary's own signed releases** — wire GPG-signed publishing of Relikqary itself (Principle IV),
  out of scope while no Relikqary artifact is published.
