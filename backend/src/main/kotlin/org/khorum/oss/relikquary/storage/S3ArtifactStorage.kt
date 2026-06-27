package org.khorum.oss.relikquary.storage

import org.khorum.oss.relikquary.config.StorageProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * S3-compatible [ArtifactStorage] (FR-001). The validated Maven-layout key is used directly as the S3
 * object key (FR-005); bytes are preserved exactly (FR-004); an absent key surfaces as null so the
 * protocol layer returns a clean 404 (FR-006).
 */
@Component
@ConditionalOnProperty(name = ["relikquary.storage.backend"], havingValue = "s3")
class S3ArtifactStorage(
    private val s3: S3Client,
    properties: StorageProperties,
) : ArtifactStorage {

    private val bucket: String = properties.s3.bucket

    // NoSuchKeyException is the expected "absent" signal from S3, not an error to propagate.
    @Suppress("SwallowedException")
    override fun exists(key: String): Boolean =
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())
            true
        } catch (e: NoSuchKeyException) {
            false
        }

    @Suppress("SwallowedException")
    override fun openRead(key: String): StoredArtifact? =
        try {
            val response = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())
            StoredArtifact(response, response.response().contentLength())
        } catch (e: NoSuchKeyException) {
            null
        }

    override fun write(key: String, content: InputStream): Long {
        // putObject needs the content length; buffer to a temp file to obtain it without holding the
        // whole artifact in memory, then upload.
        val tmp = Files.createTempFile("relikquary-s3-", ".tmp")
        try {
            val bytes = content.use { Files.copy(it, tmp, StandardCopyOption.REPLACE_EXISTING) }
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromFile(tmp))
            return bytes
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}
