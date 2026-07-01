package org.khorum.oss.relikquary.security

/**
 * What an API token is allowed to do (feature 016, Phase 3). [READ] authenticates the owner for reads
 * only; [PUBLISH] additionally grants the publish authority. Scope caps the default authorization policy
 * (open reads, PUBLISH-gated writes).
 */
enum class TokenScope {
    READ,
    PUBLISH,
    ;

    companion object {
        /** Parses a wire value ("read"/"publish", case-insensitive), or null if unrecognized. */
        fun parse(value: String?): TokenScope? =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
    }
}
