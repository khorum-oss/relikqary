package org.khorum.oss.relikquary.security

import org.khorum.oss.relikquary.config.RepositoryProperties
import org.khorum.oss.relikquary.config.SecurityProperties
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component

/**
 * The single per-repository authorization decision point (feature 007), consulted by both the Spring
 * Security [RepositoryAuthorizationManager] (single known repo) and the
 * [org.khorum.oss.relikquary.repository.RepositoryResolver] (group members). Pure and side-effect free
 * so it is safe to call from either place.
 *
 * Defaults preserve current behaviour: READ with no grant is open; PUBLISH/DELETE with no grant require
 * the global `PUBLISH` role. An explicit grant list overrides the default for that action — a principal
 * matches if their username is listed or they hold a listed `@role`.
 */
@Component
class RepositoryAuthorizer(private val security: SecurityProperties) {

    fun permits(repo: RepositoryProperties.Repo, action: Action, authentication: Authentication?): Boolean {
        if (!security.enabled) return true
        val grants = grantsFor(repo, action)
        return if (grants == null) {
            when (action) {
                Action.READ -> true
                Action.PUBLISH, Action.DELETE -> hasRole(authentication, PUBLISH_ROLE)
            }
        } else {
            matches(authentication, grants)
        }
    }

    private fun grantsFor(repo: RepositoryProperties.Repo, action: Action): List<String>? {
        val access = repo.access ?: return null
        return when (action) {
            Action.READ -> access.read
            Action.PUBLISH -> access.publish
            Action.DELETE -> access.delete
        }
    }

    /** A grant list matches if the user's name is listed, or they hold a listed `@role`. */
    private fun matches(authentication: Authentication?, grants: List<String>): Boolean {
        if (authentication == null || !authentication.isAuthenticated || isAnonymous(authentication)) {
            return false
        }
        return grants.any { grant ->
            if (grant.startsWith(ROLE_PREFIX)) {
                hasRole(authentication, grant.removePrefix(ROLE_PREFIX))
            } else {
                grant == authentication.name
            }
        }
    }

    private fun hasRole(authentication: Authentication?, role: String): Boolean {
        if (authentication == null || !authentication.isAuthenticated || isAnonymous(authentication)) {
            return false
        }
        val authority = SPRING_ROLE_PREFIX + role
        return authentication.authorities.any { it.authority == authority }
    }

    private fun isAnonymous(authentication: Authentication): Boolean =
        authentication.authorities.any { it.authority == ANONYMOUS_AUTHORITY }

    private companion object {
        const val PUBLISH_ROLE = "PUBLISH"
        const val ROLE_PREFIX = "@"
        const val SPRING_ROLE_PREFIX = "ROLE_"
        const val ANONYMOUS_AUTHORITY = "ROLE_ANONYMOUS"
    }
}
