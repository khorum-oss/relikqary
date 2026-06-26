package org.khorum.oss.relikqary.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Where artifacts are durably persisted. The backend is configuration-driven (FR-002): `filesystem`
 * (default) writes to a local directory; `s3` writes to an S3-compatible bucket (DigitalOcean Spaces,
 * MinIO, AWS S3, …). Switching backends changes only where bytes live, not the wire layout.
 */
@ConfigurationProperties(prefix = "relikqary.storage")
data class StorageProperties(
    val backend: Backend = Backend.FILESYSTEM,
    val filesystem: Filesystem = Filesystem(),
    val s3: S3 = S3(),
) {
    enum class Backend { FILESYSTEM, S3 }

    data class Filesystem(
        /** Base directory under which the Maven repository layout is materialised. */
        val root: String = "./relikqary-store",
    )

    /** S3-compatible backend settings. Credentials come from config/env and are never committed. */
    data class S3(
        /** Service endpoint, e.g. https://nyc3.digitaloceanspaces.com (override for Spaces/MinIO). */
        val endpoint: String = "",
        val region: String = "us-east-1",
        val bucket: String = "",
        val accessKey: String = "",
        val secretKey: String = "",
        /** Path-style addressing (required by MinIO and many S3-compatible endpoints). */
        val pathStyleAccess: Boolean = true,
    )
}
