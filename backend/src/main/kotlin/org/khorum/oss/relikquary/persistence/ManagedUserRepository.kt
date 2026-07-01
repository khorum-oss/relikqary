package org.khorum.oss.relikquary.persistence

import org.springframework.data.jpa.repository.JpaRepository

/** Spring Data JPA repository for [ManagedUser] rows (feature 016, Phase 3). */
interface ManagedUserRepository : JpaRepository<ManagedUser, String> {

    fun findByUsername(username: String): ManagedUser?

    fun existsByUsername(username: String): Boolean
}
