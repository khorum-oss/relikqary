package org.khorum.oss.relikquary.persistence

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Operator-selectable persistence backend for application state (feature 016, Phase 3). Artifact bytes
 * are never stored here — only tokens, managed users, publish history, and runtime settings.
 *
 *  - [Backend.SQLITE] (default): an embedded, file-backed database, no external service.
 *  - [Backend.POSTGRES]: an external PostgreSQL for shared / HA deployments.
 *
 * The schema is managed by Liquibase (see db/changelog), so Hibernate does not touch it — [ddlAuto]
 * defaults to `none`. Override only if you disable Liquibase and want Hibernate to manage the schema.
 */
@ConfigurationProperties("relikquary.persistence")
data class PersistenceProperties(
    val backend: Backend = Backend.SQLITE,
    val sqlite: Sqlite = Sqlite(),
    val postgres: Postgres = Postgres(),
    val ddlAuto: String = "none",
) {
    enum class Backend { SQLITE, POSTGRES }

    data class Sqlite(val path: String = "relikquary.db")

    data class Postgres(
        val url: String? = null,
        val username: String? = null,
        val password: String? = null,
    )
}
