package org.khorum.oss.relikqary.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Publish-time policy. The re-publish behaviour is operator-configurable (FR-010) and defaults to
 * standard Maven semantics: an existing RELEASE coordinate is immutable.
 */
@ConfigurationProperties(prefix = "relikqary.publish")
data class PublishProperties(
    val releasePolicy: ReleasePolicy = ReleasePolicy.REJECT,
) {
    enum class ReleasePolicy {
        /** Reject a re-publish of an existing RELEASE file with 409, leaving stored bytes unchanged. */
        REJECT,

        /** Allow a re-publish of an existing RELEASE file to overwrite the stored bytes. */
        OVERWRITE,
    }
}
