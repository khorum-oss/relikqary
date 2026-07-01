package org.khorum.oss.relikquary.protocol.dto

import java.time.Instant

/** Request to mint a token (feature 016, Phase 3): a label and a scope ("read" or "publish"). */
data class CreateTokenRequest(
    val name: String? = null,
    val scope: String? = null,
)

/** Response to token creation — the only time the plaintext [secret] is ever returned. */
data class CreatedTokenResponse(
    val id: String,
    val name: String,
    val scope: String,
    val createdAt: Instant,
    val secret: String,
)

/** A token as listed (never includes the secret). */
data class TokenResponse(
    val id: String,
    val name: String,
    val owner: String,
    val scope: String,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
    val revoked: Boolean,
)
