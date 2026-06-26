package org.khorum.oss.relikqary.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Where artifacts are durably persisted. The location is fully configuration-driven so an operator
 * can point Relikqary at a different filesystem location without any code change (FR-007).
 */
@ConfigurationProperties(prefix = "relikqary.storage")
data class StorageProperties(
    val filesystem: Filesystem = Filesystem(),
) {
    data class Filesystem(
        /** Base directory under which the Maven repository layout is materialised. */
        val root: String = "./relikqary-store",
    )
}
