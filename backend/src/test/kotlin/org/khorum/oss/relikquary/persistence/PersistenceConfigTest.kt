package org.khorum.oss.relikquary.persistence

import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant
import javax.sql.DataSource

/**
 * Default (embedded SQLite) persistence (feature 016, Phase 3): the schema is created automatically and a
 * row round-trips through the real datasource. The SQLite file lives under the build directory in tests
 * (see backend/build.gradle.kts); file-backed storage is inherently durable across restarts.
 */
@SpringBootTest(properties = ["relikquary.security.enabled=false"])
class PersistenceConfigTest {

    @Autowired
    lateinit var settings: SettingRepository

    @Autowired
    lateinit var dataSource: DataSource

    @Test
    fun `sqlite is the default backend`() {
        assertTrue((dataSource as HikariDataSource).jdbcUrl.startsWith("jdbc:sqlite:"))
    }

    @Test
    fun `a setting round-trips through the database`() {
        val row = Setting().apply {
            key = "persistence.smoke"
            value = "kept"
            updatedAt = Instant.parse("2026-06-30T00:00:00Z")
            updatedBy = "tester"
        }
        settings.saveAndFlush(row)

        val found = settings.findById("persistence.smoke").orElse(null)
        assertNotNull(found) { "the saved setting should be readable back from the database" }
        assertEquals("kept", found.value)
        assertEquals("tester", found.updatedBy)
    }
}
