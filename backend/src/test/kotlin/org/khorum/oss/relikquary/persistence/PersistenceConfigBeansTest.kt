package org.khorum.oss.relikquary.persistence

import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Unit coverage for backend selection (feature 016, Phase 3): each backend yields the right datasource
 * URL/driver and Hibernate dialect, and a postgres backend without a URL fails fast. The HikariDataSource
 * built here is lazy (no connection is opened), so these assertions need no running database — the real
 * round-trips live in [PersistenceConfigTest] (SQLite) and [PersistencePostgresIT] (PostgreSQL).
 */
class PersistenceConfigBeansTest {

    private fun dialectOf(config: PersistenceConfig): String {
        val properties = HashMap<String, Any>()
        config.persistenceHibernateCustomizer().customize(properties)
        return properties["hibernate.dialect"] as String
    }

    @Test
    fun `sqlite backend builds a file-backed datasource with the sqlite dialect`() {
        val config = PersistenceConfig(
            PersistenceProperties(sqlite = PersistenceProperties.Sqlite(path = "build/test-unit/x.db")),
        )
        val dataSource = config.dataSource() as HikariDataSource
        try {
            assertEquals("jdbc:sqlite:build/test-unit/x.db", dataSource.jdbcUrl)
            assertEquals(1, dataSource.maximumPoolSize)
            assertEquals("org.hibernate.community.dialect.SQLiteDialect", dialectOf(config))
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `postgres backend builds a postgres datasource with the postgres dialect`() {
        val config = PersistenceConfig(
            PersistenceProperties(
                backend = PersistenceProperties.Backend.POSTGRES,
                postgres = PersistenceProperties.Postgres(
                    url = "jdbc:postgresql://db:5432/relikquary",
                    username = "rq",
                    password = "secret",
                ),
            ),
        )
        val dataSource = config.dataSource() as HikariDataSource
        try {
            assertEquals("jdbc:postgresql://db:5432/relikquary", dataSource.jdbcUrl)
            assertEquals("rq", dataSource.username)
            assertEquals("org.hibernate.dialect.PostgreSQLDialect", dialectOf(config))
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `postgres without a url fails fast`() {
        val config = PersistenceConfig(
            PersistenceProperties(backend = PersistenceProperties.Backend.POSTGRES),
        )
        val error = assertThrows(IllegalStateException::class.java) { config.dataSource() }
        assertEquals(true, error.message!!.contains("url"))
    }
}
