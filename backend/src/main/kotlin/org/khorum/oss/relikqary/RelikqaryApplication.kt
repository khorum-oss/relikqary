package org.khorum.oss.relikqary

import org.khorum.oss.relikqary.config.PublishProperties
import org.khorum.oss.relikqary.config.RepositoryProperties
import org.khorum.oss.relikqary.config.SecurityProperties
import org.khorum.oss.relikqary.config.StorageProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(
    StorageProperties::class,
    PublishProperties::class,
    SecurityProperties::class,
    RepositoryProperties::class,
)
class RelikqaryApplication

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<RelikqaryApplication>(*args)
}
