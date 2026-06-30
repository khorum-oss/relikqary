package org.khorum.oss.relikquary.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * A persisted, administrator-editable runtime setting (feature 016, Phase 3). This is also the first
 * persisted entity, so it doubles as the round-trip proof that the operator-selected datastore (embedded
 * SQLite by default, optional external PostgreSQL) is wired and that Hibernate generates the schema on
 * both backends. Written as a JavaBean-style class (no-arg constructor + mutable properties) so it needs
 * no extra Kotlin JPA compiler plugin.
 */
@Entity
@Table(name = "setting")
class Setting {

    @Id
    @Column(name = "setting_key", length = KEY_LENGTH)
    var key: String = ""

    @Column(name = "value", length = VALUE_LENGTH)
    var value: String = ""

    @Column(name = "updated_at")
    var updatedAt: Instant = Instant.EPOCH

    @Column(name = "updated_by")
    var updatedBy: String? = null

    private companion object {
        const val KEY_LENGTH = 200
        const val VALUE_LENGTH = 4000
    }
}
