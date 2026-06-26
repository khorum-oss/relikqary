package org.khorum.oss.relikqary.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Authentication settings (feature 002). Auth is ON by default; publishing requires a configured user
 * holding the `PUBLISH` role, while reads stay open. Setting [enabled] to false disables auth entirely
 * (local-dev opt-out, FR-007/FR-008).
 */
@ConfigurationProperties(prefix = "relikqary.security")
data class SecurityProperties(
    val enabled: Boolean = true,
    val users: List<User> = emptyList(),
) {
    /**
     * A configured principal. [password] is matched by a delegating encoder, so it carries an encoder
     * prefix: `{bcrypt}…` for real deployments, `{noop}…` for tests/local.
     */
    data class User(
        val username: String = "",
        val password: String = "",
        val roles: List<String> = listOf("PUBLISH"),
    )
}
