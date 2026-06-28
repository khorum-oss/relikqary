package org.khorum.oss.relikquary.repository

import org.khorum.oss.relikquary.config.RepositoryProperties
import org.springframework.stereotype.Component

/** Thrown when a request targets a repository name that is not configured (⇒ HTTP 404). */
class RepositoryNotFoundException(name: String) : RuntimeException("no such repository: $name")

/** Thrown at startup when the configured repositories are invalid (⇒ context fails to start). */
class InvalidRepositoryConfigException(message: String) : RuntimeException(message)

/** Looks up configured repositories by name and validates the configuration at startup (feature 006). */
@Component
class RepositoryRegistry(properties: RepositoryProperties) {

    private val byName: Map<String, RepositoryProperties.Repo> =
        properties.repositories.filter { it.name.isNotBlank() }.associateBy { it.name }

    init {
        byName.values.forEach(::validate)
    }

    /** Returns the configured repository, or throws [RepositoryNotFoundException] if unknown. */
    fun require(name: String): RepositoryProperties.Repo =
        byName[name] ?: throw RepositoryNotFoundException(name)

    /** All configured repositories, in declaration order. */
    fun all(): List<RepositoryProperties.Repo> = byName.values.toList()

    private fun validate(repo: RepositoryProperties.Repo) {
        when (repo.kind) {
            RepositoryKind.HOSTED -> Unit
            RepositoryKind.PROXY ->
                if (repo.remoteUrl.isNullOrBlank()) {
                    fail("proxy repository '${repo.name}' requires a remoteUrl")
                }
            RepositoryKind.GROUP -> validateGroup(repo)
        }
    }

    private fun validateGroup(repo: RepositoryProperties.Repo) {
        if (repo.members.isEmpty()) fail("group repository '${repo.name}' requires at least one member")
        repo.members.forEach { member ->
            if (member == repo.name) fail("group repository '${repo.name}' cannot reference itself")
            val target = byName[member]
                ?: fail("group repository '${repo.name}' references unknown member '$member'")
            if (target.kind == RepositoryKind.GROUP) {
                fail("group repository '${repo.name}' member '$member' is a group (nested groups unsupported)")
            }
        }
    }

    private fun fail(message: String): Nothing = throw InvalidRepositoryConfigException(message)
}
