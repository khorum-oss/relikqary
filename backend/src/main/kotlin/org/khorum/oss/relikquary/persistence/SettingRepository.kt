package org.khorum.oss.relikquary.persistence

import org.springframework.data.jpa.repository.JpaRepository

/** Spring Data JPA repository for [Setting] rows (feature 016, Phase 3). */
interface SettingRepository : JpaRepository<Setting, String>
