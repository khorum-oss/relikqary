package org.khorum.oss.relikqary.ingestion

import org.khorum.oss.relikqary.config.PublishProperties
import org.khorum.oss.relikqary.coordinate.PathKind
import org.khorum.oss.relikqary.coordinate.RepositoryPath
import org.springframework.stereotype.Component

/**
 * Decides whether a publish (PUT) may proceed, enforcing release immutability (FR-010).
 *
 * A new path is always allowed. For an existing path: SNAPSHOT versions and artifact metadata are
 * always overwritable; an existing RELEASE coordinate file is rejected unless the operator has
 * configured the `OVERWRITE` policy.
 */
@Component
class RepublishPolicy(private val properties: PublishProperties) {

    fun isAllowed(path: RepositoryPath, alreadyExists: Boolean): Boolean {
        if (!alreadyExists) return true
        return when (path.classify()) {
            PathKind.SNAPSHOT, PathKind.METADATA -> true
            PathKind.RELEASE -> properties.releasePolicy == PublishProperties.ReleasePolicy.OVERWRITE
        }
    }
}
