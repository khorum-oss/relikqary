package org.khorum.oss.relikquary.protocol.dto

import java.time.Instant

/** Request to create a managed user (feature 016, Phase 3). `roles` are role names, e.g. ["PUBLISH"]. */
data class CreateUserRequest(
    val username: String? = null,
    val email: String? = null,
    val password: String? = null,
    val roles: List<String>? = null,
)

/** A managed user as listed — never carries the password hash. */
data class UserResponse(
    val id: String,
    val username: String,
    val email: String?,
    val roles: List<String>,
    val lastActiveAt: Instant?,
    val createdAt: Instant,
)
