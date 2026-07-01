package org.khorum.oss.relikquary.protocol.admin

import org.khorum.oss.relikquary.persistence.ApiToken
import org.khorum.oss.relikquary.protocol.dto.CreateTokenRequest
import org.khorum.oss.relikquary.protocol.dto.CreatedTokenResponse
import org.khorum.oss.relikquary.protocol.dto.TokenResponse
import org.khorum.oss.relikquary.security.ApiTokenService
import org.khorum.oss.relikquary.security.TokenScope
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Admin API for API tokens (feature 016, Phase 3): create (secret shown once), list (never re-revealing
 * the secret), and revoke. The whole admin surface under /api/admin requires the global PUBLISH authority
 * (enforced by RepositoryAuthorizationManager). A created token is owned by its creator.
 */
@RestController
@RequestMapping("/api/admin/tokens")
class TokenController(private val tokens: ApiTokenService) {

    @PostMapping
    fun create(@RequestBody request: CreateTokenRequest): ResponseEntity<CreatedTokenResponse> {
        val name = request.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "token name is required")
        val scope = TokenScope.parse(request.scope)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "scope must be 'read' or 'publish'")
        val issued = tokens.issue(name, scope, owner())
        val body = CreatedTokenResponse(
            id = issued.token.id,
            name = issued.token.name,
            scope = issued.token.scope.name.lowercase(),
            createdAt = issued.token.createdAt,
            secret = issued.secret,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(body)
    }

    @GetMapping
    fun list(): List<TokenResponse> = tokens.list().map(::toResponse)

    @DeleteMapping("/{id}")
    fun revoke(@PathVariable id: String): ResponseEntity<Void> =
        if (tokens.revoke(id)) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()

    private fun toResponse(token: ApiToken) = TokenResponse(
        id = token.id,
        name = token.name,
        owner = token.ownerUsername,
        scope = token.scope.name.lowercase(),
        createdAt = token.createdAt,
        lastUsedAt = token.lastUsedAt,
        revoked = token.revoked,
    )

    private fun owner(): String =
        SecurityContextHolder.getContext().authentication?.name?.takeIf { it.isNotBlank() } ?: "system"
}
