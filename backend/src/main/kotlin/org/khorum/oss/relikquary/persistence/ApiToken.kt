package org.khorum.oss.relikquary.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.khorum.oss.relikquary.security.TokenScope
import java.time.Instant

/**
 * A named, scoped API token (feature 016, Phase 3). The secret is shown once at creation and stored only
 * as a hash ([secretHash]) — never recoverable. A token authenticates as its [ownerUsername] (so the
 * owner's per-repo grants apply) capped by [scope]. A non-null [revokedAt] disables it.
 */
@Entity
@Table(name = "api_token")
class ApiToken {

    @Id
    @Column(name = "id", length = ID_LENGTH)
    var id: String = ""

    @Column(name = "name", length = NAME_LENGTH)
    var name: String = ""

    @Column(name = "owner_username", length = NAME_LENGTH)
    var ownerUsername: String = ""

    /** Deterministic SHA-256 of the high-entropy secret; the lookup key for authentication. */
    @Column(name = "secret_hash", length = HASH_LENGTH, unique = true)
    var secretHash: String = ""

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", length = SCOPE_LENGTH)
    var scope: TokenScope = TokenScope.READ

    @Column(name = "created_at")
    var createdAt: Instant = Instant.EPOCH

    @Column(name = "last_used_at")
    var lastUsedAt: Instant? = null

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null

    val revoked: Boolean get() = revokedAt != null

    private companion object {
        const val ID_LENGTH = 40
        const val NAME_LENGTH = 200
        const val HASH_LENGTH = 64
        const val SCOPE_LENGTH = 16
    }
}
