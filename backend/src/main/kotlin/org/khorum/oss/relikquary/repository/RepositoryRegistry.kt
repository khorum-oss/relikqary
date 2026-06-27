package org.khorum.oss.relikquary.repository

import org.khorum.oss.relikquary.config.RepositoryProperties
import org.springframework.stereotype.Component

/** Thrown when a request targets a repository name that is not configured (⇒ HTTP 404). */
class RepositoryNotFoundException(name: String) : RuntimeException("no such repository: $name")

/** Looks up configured repositories by name. */
@Component
class RepositoryRegistry(properties: RepositoryProperties) {

    private val byName: Map<String, RepositoryProperties.Repo> =
        properties.repositories.filter { it.name.isNotBlank() }.associateBy { it.name }

    /** Returns the configured repository, or throws [RepositoryNotFoundException] if unknown. */
    fun require(name: String): RepositoryProperties.Repo =
        byName[name] ?: throw RepositoryNotFoundException(name)
}
