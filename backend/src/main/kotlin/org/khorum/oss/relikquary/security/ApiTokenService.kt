package org.khorum.oss.relikquary.security

import org.khorum.oss.relikquary.persistence.ApiToken
import org.khorum.oss.relikquary.persistence.ApiTokenRepository
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** A freshly issued token: the persisted entity plus its one-time-visible plaintext [secret]. */
data class IssuedToken(val token: ApiToken, val secret: String)

/**
 * Issues, lists, revokes, and authenticates API tokens (feature 016, Phase 3). Secrets are high-entropy
 * random values shown once; only a deterministic SHA-256 is stored, so a leaked database never yields a
 * usable token. Authentication looks up by that hash and rejects revoked tokens.
 */
@Service
class ApiTokenService(private val tokens: ApiTokenRepository) {

    private val random = SecureRandom()

    /** Creates a token owned by [owner]; returns the entity and the plaintext secret (shown only now). */
    fun issue(name: String, scope: TokenScope, owner: String): IssuedToken {
        val secret = SECRET_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes())
        val token = ApiToken().apply {
            id = UUID.randomUUID().toString()
            this.name = name
            ownerUsername = owner
            secretHash = hash(secret)
            this.scope = scope
            createdAt = Instant.now()
        }
        return IssuedToken(tokens.save(token), secret)
    }

    /** All tokens (secrets are never exposed). */
    fun list(): List<ApiToken> = tokens.findAll()

    /** Revokes the token with [id]; returns false if it does not exist or was already revoked. */
    fun revoke(id: String): Boolean {
        val token = tokens.findById(id).orElse(null) ?: return false
        if (token.revoked) return false
        token.revokedAt = Instant.now()
        tokens.save(token)
        return true
    }

    /** Resolves an active token by its plaintext secret, or null if unknown/revoked; touches last-used. */
    fun authenticate(secret: String): ApiToken? {
        val token = tokens.findBySecretHash(hash(secret)) ?: return null
        if (token.revoked) return null
        touchLastUsed(token)
        return token
    }

    private fun touchLastUsed(token: ApiToken) {
        val now = Instant.now()
        val last = token.lastUsedAt
        // Throttle the write: a heavily used token (CI resolves) must not write on every request.
        if (last == null || Duration.between(last, now) > LAST_USED_THROTTLE) {
            token.lastUsedAt = now
            tokens.save(token)
        }
    }

    private fun randomBytes(): ByteArray = ByteArray(SECRET_BYTES).also(random::nextBytes)

    companion object {
        const val SECRET_PREFIX = "rlq_"
        private const val SECRET_BYTES = 32
        private val LAST_USED_THROTTLE: Duration = Duration.ofMinutes(1)

        /** Whether a presented credential looks like an API token (so the token path handles it). */
        fun isTokenSecret(candidate: String): Boolean = candidate.startsWith(SECRET_PREFIX)

        /** Deterministic SHA-256 (hex) of a secret — high-entropy secrets need no per-token salt. */
        fun hash(secret: String): String =
            MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
