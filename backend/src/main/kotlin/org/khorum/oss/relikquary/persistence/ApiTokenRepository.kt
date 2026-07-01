package org.khorum.oss.relikquary.persistence

import org.springframework.data.jpa.repository.JpaRepository

/** Spring Data JPA repository for [ApiToken] rows (feature 016, Phase 3). */
interface ApiTokenRepository : JpaRepository<ApiToken, String> {

    /** Looks up a token by its deterministic secret hash (the authentication lookup key). */
    fun findBySecretHash(secretHash: String): ApiToken?
}
