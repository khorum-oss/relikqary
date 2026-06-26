package org.khorum.oss.relikqary.config

import org.khorum.oss.relikqary.repository.RepositoryType
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The set of named, typed repositories Relikqary serves (feature 004). Each request is addressed by a
 * repository name prefix (`/{repo}/…`); there is no implicit repository at the root.
 */
@ConfigurationProperties(prefix = "relikqary")
data class RepositoryProperties(
    val repositories: List<Repo> = emptyList(),
) {
    data class Repo(
        val name: String = "",
        val type: RepositoryType = RepositoryType.MIXED,
    )
}
