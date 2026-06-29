package org.khorum.oss.relikquary.storage

import org.khorum.oss.relikquary.config.StorageProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.Delete
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
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

    override fun list(prefix: String): List<StorageEntry> {
        val norm = normalizeFolder(prefix)
        val request = ListObjectsV2Request.builder().bucket(bucket).prefix(norm).delimiter("/").build()
        val pages = s3.listObjectsV2Paginator(request)
        val entries = mutableListOf<StorageEntry>()
        pages.commonPrefixes().forEach { cp ->
            val name = cp.prefix().removePrefix(norm).trimEnd('/')
            if (name.isNotEmpty()) entries += StorageEntry(name, isDirectory = true)
        }
        pages.contents().forEach { obj ->
            val name = obj.key().removePrefix(norm)
            if (name.isNotEmpty() && !name.contains('/')) {
                entries += StorageEntry(name, false, obj.size(), obj.lastModified())
            }
        }
        return entries.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
    }

    override fun walk(prefix: String): List<StoredObject> {
        val norm = normalizeFolder(prefix)
        val request = ListObjectsV2Request.builder().bucket(bucket).prefix(norm).build()
        return s3.listObjectsV2Paginator(request).contents()
            .map { StoredObject(it.key(), it.size(), it.lastModified()) }
            .toList()
    }

    override fun delete(key: String): Boolean {
        if (!exists(key)) return false
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build())
        return true
    }

    override fun deletePrefix(prefix: String): Int {
        val norm = normalizeFolder(prefix)
        val request = ListObjectsV2Request.builder().bucket(bucket).prefix(norm).build()
        val keys = s3.listObjectsV2Paginator(request).contents().map { it.key() }.toList()
        keys.chunked(DELETE_BATCH).forEach { batch ->
            val ids = batch.map { ObjectIdentifier.builder().key(it).build() }
            s3.deleteObjects(
                DeleteObjectsRequest.builder().bucket(bucket).delete(Delete.builder().objects(ids).build()).build(),
            )
        }
        return keys.size
    }

    // A failed headBucket (unreachable endpoint, bad credentials, missing bucket) is the "not healthy"
    // signal — swallowed deliberately and reported without exposing the endpoint/bucket or credentials.
    @Suppress("SwallowedException")
    override fun probe(): StorageProbe =
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build())
            StorageProbe(healthy = true, backend = "s3")
        } catch (e: SdkException) {
            StorageProbe(healthy = false, backend = "s3", detail = "bucket is not reachable")
        }

    private fun normalizeFolder(prefix: String): String =
        if (prefix.isEmpty() || prefix.endsWith("/")) prefix else "$prefix/"

    private companion object {
        const val DELETE_BATCH = 1000
    }
}
