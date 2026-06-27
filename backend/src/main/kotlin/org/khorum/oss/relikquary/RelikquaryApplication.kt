package org.khorum.oss.relikquary

import org.khorum.oss.relikquary.config.PublishProperties
import org.khorum.oss.relikquary.config.RepositoryProperties
import org.khorum.oss.relikquary.config.SecurityProperties
import org.khorum.oss.relikquary.config.StorageProperties
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
class RelikquaryApplication

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<RelikquaryApplication>(*args)
}
