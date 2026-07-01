package org.khorum.oss.relikquary.persistence

import com.zaxxer.hikari.HikariDataSource
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer
import org.springframework.boot.jdbc.DataSourceBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource

/**
 * Builds the datasource for the selected persistence backend (feature 016, Phase 3) and points Hibernate
 * at the matching dialect, so the rest of the app uses plain Spring Data JPA regardless of engine.
 * Defining the [DataSource] bean here makes Spring Boot's datasource auto-configuration back off; the
 * Hibernate customizer supplies the dialect and `ddl-auto` (Hibernate generates portable schema).
 */
@Configuration
@EnableConfigurationProperties(PersistenceProperties::class)
class PersistenceConfig(private val properties: PersistenceProperties) {

    @Bean
    fun dataSource(): DataSource = when (properties.backend) {
        PersistenceProperties.Backend.SQLITE -> sqliteDataSource()
        PersistenceProperties.Backend.POSTGRES -> postgresDataSource()
    }

    @Bean
    fun persistenceHibernateCustomizer(): HibernatePropertiesCustomizer =
        HibernatePropertiesCustomizer { hibernateProperties ->
            hibernateProperties["hibernate.dialect"] = when (properties.backend) {
                PersistenceProperties.Backend.SQLITE -> SQLITE_DIALECT
                PersistenceProperties.Backend.POSTGRES -> POSTGRES_DIALECT
            }
            hibernateProperties["hibernate.hbm2ddl.auto"] = properties.ddlAuto
        }

    private fun sqliteDataSource(): DataSource {
        val path = properties.sqlite.path
        Path.of(path).parent?.let { runCatching { Files.createDirectories(it) } }
        val dataSource = DataSourceBuilder.create()
            .driverClassName("org.sqlite.JDBC")
            .url("jdbc:sqlite:$path")
            .type(HikariDataSource::class.java)
            .build()
        // SQLite is a single-writer engine; one pooled connection avoids "database is locked" contention.
        dataSource.maximumPoolSize = 1
        return dataSource
    }

    private fun postgresDataSource(): DataSource {
        val url = properties.postgres.url?.takeIf { it.isNotBlank() }
            ?: error("relikquary.persistence.backend=postgres requires relikquary.persistence.postgres.url")
        return DataSourceBuilder.create()
            .driverClassName("org.postgresql.Driver")
            .url(url)
            .username(properties.postgres.username)
            .password(properties.postgres.password)
            .type(HikariDataSource::class.java)
            .build()
    }

    private companion object {
        const val SQLITE_DIALECT = "org.hibernate.community.dialect.SQLiteDialect"
        const val POSTGRES_DIALECT = "org.hibernate.dialect.PostgreSQLDialect"
    }
}
