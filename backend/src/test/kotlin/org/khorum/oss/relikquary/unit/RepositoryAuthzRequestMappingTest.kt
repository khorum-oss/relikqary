package org.khorum.oss.relikquary.unit

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.khorum.oss.relikquary.config.RepositoryProperties
import org.khorum.oss.relikquary.config.RepositoryProperties.Repo
import org.khorum.oss.relikquary.config.RepositoryProperties.RepositoryAccess
import org.khorum.oss.relikquary.config.SecurityProperties
import org.khorum.oss.relikquary.repository.RepositoryKind
import org.khorum.oss.relikquary.repository.RepositoryRegistry
import org.khorum.oss.relikquary.security.RepositoryAuthorizationManager
import org.khorum.oss.relikquary.security.RepositoryAuthorizer
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.web.access.intercept.RequestAuthorizationContext
import java.util.function.Supplier

/**
 * Verifies the [RepositoryAuthorizationManager] request→(repo, action) mapping and the resulting
 * grant/deny decisions across the Maven path, the browse API, and the always-granted shapes.
 */
class RepositoryAuthzRequestMappingTest {

    private val registry = RepositoryRegistry(
        RepositoryProperties(
            listOf(
                Repo(name = "releases"),
                Repo(
                    name = "private",
                    access = RepositoryAccess(
                        read = listOf("alice"), publish = listOf("alice"), delete = listOf("alice"),
                    ),
                ),
                Repo(name = "maven-central", kind = RepositoryKind.PROXY, remoteUrl = "https://up.example/m2"),
                Repo(name = "public", kind = RepositoryKind.GROUP, members = listOf("releases", "maven-central")),
            ),
        ),
    )
    private val manager = RepositoryAuthorizationManager(registry, RepositoryAuthorizer(SecurityProperties(enabled = true)))

    private fun user(name: String, vararg roles: String): Authentication =
        UsernamePasswordAuthenticationToken(name, "x", roles.map { SimpleGrantedAuthority("ROLE_$it") })

    private val anonymous: Authentication =
        AnonymousAuthenticationToken("k", "anonymousUser", listOf(SimpleGrantedAuthority("ROLE_ANONYMOUS")))

    private fun granted(method: String, uri: String, auth: Authentication): Boolean {
        val request = MockHttpServletRequest(method, uri)
        val decision = manager.authorize(Supplier { auth }, RequestAuthorizationContext(request))
        return decision.isGranted
    }

    @Test
    fun `open hosted read is granted to anyone`() {
        assertTrue(granted("GET", "/releases/com/x/1.0/x-1.0.jar", anonymous))
    }

    @Test
    fun `private hosted read enforces the read grant`() {
        assertTrue(granted("GET", "/private/com/x/1.0/x-1.0.jar", user("alice")))
        assertFalse(granted("GET", "/private/com/x/1.0/x-1.0.jar", user("bob")))
        assertFalse(granted("GET", "/private/com/x/1.0/x-1.0.jar", anonymous))
    }

    @Test
    fun `hosted publish defaults to the global PUBLISH role`() {
        assertTrue(granted("PUT", "/releases/com/x/1.0/x-1.0.jar", user("ci", "PUBLISH")))
        assertFalse(granted("PUT", "/releases/com/x/1.0/x-1.0.jar", anonymous))
    }

    @Test
    fun `publish to a proxy or group is granted (controller returns 405)`() {
        assertTrue(granted("PUT", "/maven-central/com/x/1.0/x-1.0.jar", anonymous))
        assertTrue(granted("PUT", "/public/com/x/1.0/x-1.0.jar", anonymous))
    }

    @Test
    fun `group read is granted (deferred to the resolver)`() {
        assertTrue(granted("GET", "/public/com/x/1.0/x-1.0.jar", anonymous))
    }

    @Test
    fun `unknown repository is granted (controller returns 404)`() {
        assertTrue(granted("GET", "/nope/com/x/1.0/x-1.0.jar", anonymous))
    }

    @Test
    fun `browse contents enforces the read grant and the repository list is open`() {
        assertFalse(granted("GET", "/api/repositories/private/contents/com/x", user("bob")))
        assertTrue(granted("GET", "/api/repositories/private/contents/com/x", user("alice")))
        assertTrue(granted("GET", "/api/repositories", anonymous))
    }

    @Test
    fun `browse delete enforces the delete grant`() {
        assertTrue(granted("DELETE", "/api/repositories/private/com/x/1.0/x-1.0.jar", user("alice")))
        assertFalse(granted("DELETE", "/api/repositories/private/com/x/1.0/x-1.0.jar", user("bob")))
    }
}
