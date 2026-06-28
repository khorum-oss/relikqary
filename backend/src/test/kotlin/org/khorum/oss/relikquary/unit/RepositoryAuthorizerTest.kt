package org.khorum.oss.relikquary.unit

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.khorum.oss.relikquary.config.RepositoryProperties.Repo
import org.khorum.oss.relikquary.config.RepositoryProperties.RepositoryAccess
import org.khorum.oss.relikquary.config.SecurityProperties
import org.khorum.oss.relikquary.security.Action
import org.khorum.oss.relikquary.security.RepositoryAuthorizer
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority

class RepositoryAuthorizerTest {

    private val enabled = RepositoryAuthorizer(SecurityProperties(enabled = true))
    private val disabled = RepositoryAuthorizer(SecurityProperties(enabled = false))

    private fun user(name: String, vararg roles: String): Authentication =
        UsernamePasswordAuthenticationToken(name, "x", roles.map { SimpleGrantedAuthority("ROLE_$it") })

    private val anonymous: Authentication =
        AnonymousAuthenticationToken("k", "anonymousUser", listOf(SimpleGrantedAuthority("ROLE_ANONYMOUS")))

    private val open = Repo(name = "open")
    private fun restricted(access: RepositoryAccess) = Repo(name = "restricted", access = access)

    @Test
    fun `disabled security permits everything`() {
        val repo = restricted(RepositoryAccess(read = listOf("alice")))
        assertTrue(disabled.permits(repo, Action.READ, anonymous))
        assertTrue(disabled.permits(repo, Action.PUBLISH, null))
    }

    @Test
    fun `read with no grant is open to anyone, including anonymous`() {
        assertTrue(enabled.permits(open, Action.READ, anonymous))
        assertTrue(enabled.permits(open, Action.READ, null))
    }

    @Test
    fun `publish and delete with no grant require the global PUBLISH role`() {
        assertTrue(enabled.permits(open, Action.PUBLISH, user("pub", "PUBLISH")))
        assertFalse(enabled.permits(open, Action.PUBLISH, user("reader", "READER")))
        assertFalse(enabled.permits(open, Action.PUBLISH, anonymous))
        assertTrue(enabled.permits(open, Action.DELETE, user("pub", "PUBLISH")))
        assertFalse(enabled.permits(open, Action.DELETE, anonymous))
    }

    @Test
    fun `read grant by username permits only listed users`() {
        val repo = restricted(RepositoryAccess(read = listOf("alice")))
        assertTrue(enabled.permits(repo, Action.READ, user("alice")))
        assertFalse(enabled.permits(repo, Action.READ, user("bob")))
        assertFalse(enabled.permits(repo, Action.READ, anonymous))
    }

    @Test
    fun `grant by role permits users holding that role`() {
        val repo = restricted(RepositoryAccess(read = listOf("@platform")))
        assertTrue(enabled.permits(repo, Action.READ, user("carol", "platform")))
        assertFalse(enabled.permits(repo, Action.READ, user("dave", "other")))
    }

    @Test
    fun `explicit publish grant overrides the global PUBLISH default`() {
        val repo = restricted(RepositoryAccess(publish = listOf("alice")))
        // alice is listed even though she lacks the global PUBLISH role
        assertTrue(enabled.permits(repo, Action.PUBLISH, user("alice")))
        // a global PUBLISH holder who is not listed is rejected
        assertFalse(enabled.permits(repo, Action.PUBLISH, user("ci", "PUBLISH")))
    }
}
