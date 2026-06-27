package org.khorum.oss.relikqary.ingestion

import org.khorum.oss.relikqary.config.PublishProperties
import org.khorum.oss.relikqary.coordinate.PathKind
import org.khorum.oss.relikqary.coordinate.RepositoryPath
import org.khorum.oss.relikqary.repository.RepositoryType
import org.springframework.stereotype.Component

/** Outcome of evaluating a publish against a repository's type and existing state (feature 004). */
enum class PublishDecision {
    /** Store the bytes. */
    ACCEPT,

    /** Existing immutable release coordinate -> HTTP 409. */
    REJECT_IMMUTABLE,

    /** Coordinate kind not accepted by this repository type -> HTTP 400. */
    REJECT_TYPE,
}

/**
 * Decides whether a publish (PUT) may proceed, enforcing per-repository type rules and release
 * immutability (FR-004/005/006). Artifact metadata is always overwritable. The global
 * `relikqary.publish.release-policy=overwrite` relaxes release immutability for release/mixed repos.
 */
@Component
class RepublishPolicy(private val properties: PublishProperties) {

    fun evaluate(type: RepositoryType, path: RepositoryPath, alreadyExists: Boolean): PublishDecision {
        val kind = path.classify()

        if (kind != PathKind.METADATA) {
            val mismatch = (type == RepositoryType.RELEASE && kind == PathKind.SNAPSHOT) ||
                (type == RepositoryType.SNAPSHOT && kind == PathKind.RELEASE)
            if (mismatch) return PublishDecision.REJECT_TYPE
        }

        if (!alreadyExists) return PublishDecision.ACCEPT

        return when (kind) {
            PathKind.METADATA, PathKind.SNAPSHOT -> PublishDecision.ACCEPT
            PathKind.RELEASE ->
                if (properties.releasePolicy == PublishProperties.ReleasePolicy.OVERWRITE) {
                    PublishDecision.ACCEPT
                } else {
                    PublishDecision.REJECT_IMMUTABLE
                }
        }
    }
}
