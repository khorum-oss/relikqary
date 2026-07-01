package org.khorum.oss.relikquary.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * A managed user account (feature 016, Phase 3, US8) — the DB-backed alternative to static-config users.
 * The password is stored only as an encoder-prefixed hash (`{bcrypt}…`). [roles] is a comma-separated
 * list of role names (no `ROLE_` prefix), e.g. "PUBLISH". Managed users coexist with config users; a
 * managed username must not collide with a config one (validated on create).
 */
@Entity
@Table(name = "managed_user")
class ManagedUser {

    @Id
    @Column(name = "id", length = ID_LENGTH)
    var id: String = ""

    @Column(name = "username", length = NAME_LENGTH, unique = true)
    var username: String = ""

    @Column(name = "email", length = NAME_LENGTH)
    var email: String? = null

    @Column(name = "password_hash", length = HASH_LENGTH)
    var passwordHash: String = ""

    /** Comma-separated role names (no ROLE_ prefix); empty means a read-only (viewer) account. */
    @Column(name = "roles", length = ROLES_LENGTH)
    var roles: String = ""

    @Column(name = "last_active_at")
    var lastActiveAt: Instant? = null

    @Column(name = "created_at")
    var createdAt: Instant = Instant.EPOCH

    /** The role names as a list. */
    fun roleList(): List<String> = roles.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private companion object {
        const val ID_LENGTH = 40
        const val NAME_LENGTH = 200
        const val HASH_LENGTH = 200
        const val ROLES_LENGTH = 500
    }
}
