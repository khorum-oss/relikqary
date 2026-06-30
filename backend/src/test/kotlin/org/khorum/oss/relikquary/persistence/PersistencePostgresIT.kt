package org.khorum.oss.relikquary.persistence

import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import javax.sql.DataSource

/**
 * The external-database path against a REAL PostgreSQL (feature 016, Phase 3, Principle II): selecting
 * the postgres backend wires the postgres datasource/dialect, the schema is generated automatically, and
 * a row round-trips — proving the operator-selectable persistence works on Postgres, not just SQLite.
 * Auto-skipped when Docker is unavailable (it runs in CI / Docker-capable environments); the SQLite
 * round-trip in [PersistenceConfigTest] covers the default path everywhere else.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = ["relikquary.security.enabled=false"])
class PersistencePostgresIT {

    @Autowired
    lateinit var settings: SettingRepository

    @Autowired
    lateinit var dataSource: DataSource

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun persistenceProps(registry: DynamicPropertyRegistry) {
            registry.add("relikquary.persistence.backend") { "postgres" }
            registry.add("relikquary.persistence.postgres.url") { postgres.jdbcUrl }
            registry.add("relikquary.persistence.postgres.username") { postgres.username }
            registry.add("relikquary.persistence.postgres.password") { postgres.password }
        }
    }

    @Test
    fun `postgres backend is selected and a setting round-trips`() {
        val url = (dataSource as HikariDataSource).jdbcUrl
        assertTrue(url.startsWith("jdbc:postgresql:")) { "expected the postgres datasource, got $url" }

        val row = Setting().apply {
            key = "persistence.postgres"
            value = "kept-pg"
            updatedAt = Instant.parse("2026-06-30T00:00:00Z")
            updatedBy = "tester"
        }
        settings.saveAndFlush(row)

        val found = settings.findById("persistence.postgres").orElse(null)
        assertNotNull(found) { "the saved setting should be readable back from PostgreSQL" }
        assertEquals("kept-pg", found.value)
    }
}
