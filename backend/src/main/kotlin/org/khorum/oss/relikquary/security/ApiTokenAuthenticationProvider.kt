package org.khorum.oss.relikquary.security

import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.stereotype.Component

/**
 * Authenticates an API token presented as the HTTP Basic password (feature 016, Phase 3): existing
 * Maven/Gradle clients send it exactly like a password, so no client change is needed. A credential that
 * doesn't look like a token is passed through (returns null) to the standard user authentication. A valid
 * token authenticates as its owner, with the PUBLISH authority only when the token's scope grants it.
 */
@Component
class ApiTokenAuthenticationProvider(private val tokens: ApiTokenService) : AuthenticationProvider {

    override fun authenticate(authentication: Authentication): Authentication? {
        val presented = authentication.credentials?.toString() ?: return null
        if (!ApiTokenService.isTokenSecret(presented)) return null
        val token = tokens.authenticate(presented)
            ?: throw BadCredentialsException("invalid or revoked API token")
        val authorities: List<GrantedAuthority> = when (token.scope) {
            TokenScope.PUBLISH -> listOf(SimpleGrantedAuthority(PUBLISH_AUTHORITY))
            TokenScope.READ -> emptyList()
        }
        return UsernamePasswordAuthenticationToken(token.ownerUsername, null, authorities)
    }

    override fun supports(authentication: Class<*>): Boolean =
        UsernamePasswordAuthenticationToken::class.java.isAssignableFrom(authentication)

    private companion object {
        const val PUBLISH_AUTHORITY = "ROLE_PUBLISH"
    }
}
