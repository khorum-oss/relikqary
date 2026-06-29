package org.khorum.oss.relikquary.security

import jakarta.servlet.http.HttpServletRequest
import org.khorum.oss.relikquary.config.RepositoryProperties
import org.khorum.oss.relikquary.repository.RepositoryKind
import org.khorum.oss.relikquary.repository.RepositoryNotFoundException
import org.khorum.oss.relikquary.repository.RepositoryRegistry
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.authorization.AuthorizationManager
import org.springframework.security.core.Authentication
import org.springframework.security.web.access.intercept.RequestAuthorizationContext
import org.springframework.stereotype.Component
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.function.Supplier

/**
 * Per-request authorization (feature 007). Parses the request into `(repository, action)`, then defers
 * the decision to [RepositoryAuthorizer] for a single known repo. Requests that aren't repo-scoped, or
 * whose decision belongs elsewhere, are granted:
 * - unknown repository ⇒ grant (the controller returns `404`; existence is not secret);
 * - GROUP repository ⇒ grant (group read is enforced per-member in the resolver);
 * - `PUT` to a PROXY/GROUP ⇒ grant (the controller returns `405`; read-only kinds precede authz);
 * - `GET /api/repositories` (the list), `/api`, the bundled UI, and unrecognized shapes ⇒ grant.
 *
 * The management endpoint `/api/cleanup` (feature 009) is not repo-scoped; it requires the global
 * `PUBLISH` authority. A denied decision becomes `401` (anonymous → Basic challenge) or `403`
 * (authenticated) via Spring's standard exception translation.
 */
@Component
class RepositoryAuthorizationManager(
    private val registry: RepositoryRegistry,
    private val authorizer: RepositoryAuthorizer,
) : AuthorizationManager<RequestAuthorizationContext> {

    override fun authorize(
        authentication: Supplier<out Authentication?>,
        context: RequestAuthorizationContext,
    ): AuthorizationDecision {
        if (decodedPath(context.request) == MANAGEMENT_CLEANUP) {
            return AuthorizationDecision(authorizer.permitsManagement(authentication.get()))
        }
        val target = target(context.request) ?: return GRANT
        val repo = repoOrNull(target.repoName) ?: return GRANT
        if (repo.kind == RepositoryKind.GROUP) return GRANT
        if (target.action == Action.PUBLISH && repo.kind != RepositoryKind.HOSTED) return GRANT
        return AuthorizationDecision(authorizer.permits(repo, target.action, authentication.get()))
    }

    private data class Target(val repoName: String, val action: Action)

    private fun decodedPath(request: HttpServletRequest): String =
        URLDecoder.decode(request.requestURI.removePrefix(request.contextPath), StandardCharsets.UTF_8).trimStart('/')

    private fun target(request: HttpServletRequest): Target? {
        val segments = decodedPath(request).split('/').filter { it.isNotEmpty() }
        return if (segments.firstOrNull() == "api") browseTarget(request, segments) else mavenTarget(request, segments)
    }

    /** Maven wire protocol: `/{repo}/{artifactPath}` — GET/HEAD ⇒ READ, PUT ⇒ PUBLISH. */
    private fun mavenTarget(request: HttpServletRequest, segments: List<String>): Target? {
        val repoName = segments.firstOrNull() ?: return null
        val action = when (request.method.uppercase()) {
            "GET", "HEAD" -> Action.READ
            "PUT" -> Action.PUBLISH
            else -> return null
        }
        return Target(repoName, action)
    }

    /** Browse API: only `/api/repositories/{repo}/...` is repo-scoped; the rest is granted. */
    private fun browseTarget(request: HttpServletRequest, segments: List<String>): Target? {
        if (segments.size < REPO_SEGMENT_INDEX + 1 || segments[1] != "repositories") return null
        val repoName = segments[REPO_SEGMENT_INDEX]
        val sub = segments.getOrNull(REPO_SEGMENT_INDEX + 1)
        return when {
            request.method.equals("DELETE", ignoreCase = true) -> Target(repoName, Action.DELETE)
            request.method.equals("GET", ignoreCase = true) && (sub == "contents" || sub == "file" || sub == "module") ->
                Target(repoName, Action.READ)
            else -> null
        }
    }

    private fun repoOrNull(name: String): RepositoryProperties.Repo? =
        try {
            registry.require(name)
        } catch (_: RepositoryNotFoundException) {
            null
        }

    private companion object {
        val GRANT = AuthorizationDecision(true)

        /** `api / repositories / {repo} / ...` — the repo name is the third segment. */
        const val REPO_SEGMENT_INDEX = 2

        /** Non-repo-scoped management endpoint requiring the global PUBLISH authority (feature 009). */
        const val MANAGEMENT_CLEANUP = "api/cleanup"
    }
}
