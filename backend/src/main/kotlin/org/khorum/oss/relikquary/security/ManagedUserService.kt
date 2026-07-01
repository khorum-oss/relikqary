package org.khorum.oss.relikquary.security

import org.khorum.oss.relikquary.config.SecurityProperties
import org.khorum.oss.relikquary.persistence.ManagedUser
import org.khorum.oss.relikquary.persistence.ManagedUserRepository
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Thrown when a requested managed username is already taken (by a config user or another managed user). */
class UsernameConflictException(username: String) : RuntimeException("username already exists: $username")

/**
 * Manages user accounts stored in the database (feature 016, Phase 3, US8) and loads them for
 * authentication. Passwords are stored only as encoder-prefixed hashes. Managed users coexist with the
 * static-config users (which always take precedence on a name clash), so introducing managed accounts
 * never locks existing config users out.
 */
@Service
class ManagedUserService(
    private val users: ManagedUserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val security: SecurityProperties,
) {

    fun list(): List<ManagedUser> = users.findAll()

    /** Creates a managed user; throws [IllegalArgumentException] on bad input or [UsernameConflictException]. */
    fun create(username: String, email: String?, password: String, roles: List<String>): ManagedUser {
        val name = username.trim()
        require(name.isNotEmpty()) { "username is required" }
        require(password.isNotEmpty()) { "password is required" }
        if (isConfigUser(name) || users.existsByUsername(name)) throw UsernameConflictException(name)
        val user = ManagedUser().apply {
            id = UUID.randomUUID().toString()
            this.username = name
            this.email = email?.trim()?.takeIf { it.isNotEmpty() }
            passwordHash = passwordEncoder.encode(password) ?: error("password encoding failed")
            this.roles = roles.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.joinToString(",")
            createdAt = Instant.now()
        }
        return users.save(user)
    }

    /** Deletes a managed user by id; returns false if it does not exist. */
    fun delete(id: String): Boolean {
        if (!users.existsById(id)) return false
        users.deleteById(id)
        return true
    }

    /** Resolves a managed user for authentication (null if unknown); touches last-active. */
    fun loadUserDetails(username: String): UserDetails? {
        val user = users.findByUsername(username) ?: return null
        touchLastActive(user)
        return User.withUsername(user.username)
            .password(user.passwordHash)
            .authorities(user.roleList().map { org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_$it") })
            .build()
    }

    private fun isConfigUser(username: String): Boolean =
        security.users.any { it.username.equals(username, ignoreCase = false) }

    private fun touchLastActive(user: ManagedUser) {
        val now = Instant.now()
        val last = user.lastActiveAt
        // Throttle: an active user must not write to the DB on every authenticated request.
        if (last == null || Duration.between(last, now) > LAST_ACTIVE_THROTTLE) {
            user.lastActiveAt = now
            users.save(user)
        }
    }

    private companion object {
        val LAST_ACTIVE_THROTTLE: Duration = Duration.ofMinutes(1)
    }
}
