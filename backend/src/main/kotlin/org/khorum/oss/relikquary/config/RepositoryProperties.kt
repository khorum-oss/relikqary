package org.khorum.oss.relikquary.config

import org.khorum.oss.relikquary.repository.RepositoryType
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The set of named, typed repositories Relikquary serves (feature 004). Each request is addressed by a
 * repository name prefix (`/{repo}/…`); there is no implicit repository at the root.
 */
@ConfigurationProperties(prefix = "relikquary")
data class RepositoryProperties(
    val repositories: List<Repo> = emptyList(),
) {
    data class Repo(
        val name: String = "",
        val type: RepositoryType = RepositoryType.MIXED,
    )
}
