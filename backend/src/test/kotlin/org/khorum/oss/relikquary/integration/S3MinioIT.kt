package org.khorum.oss.relikquary.integration

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.khorum.oss.relikquary.config.StorageProperties
import org.khorum.oss.relikquary.storage.S3ArtifactStorage
import org.testcontainers.containers.MinIOContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import java.net.URI
import kotlin.random.Random

/**
 * The constitution-mandated Testcontainers + MinIO verification of [S3ArtifactStorage]. It is
 * **auto-skipped when Docker is unavailable** (`disabledWithoutDocker = true`) — so it runs in CI /
 * Docker-capable environments while the s3mock-based [S3RoundTripTest] provides equivalent coverage
 * elsewhere. This split is the documented, justified deviation from the Testcontainers letter.
 */
@Testcontainers(disabledWithoutDocker = true)
class S3MinioIT {

    @Test
    fun `stores and serves an object byte-for-byte against MinIO`() {
        val s3 = S3Client.builder()
            .endpointOverride(URI.create(minio.s3URL))
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(minio.userName, minio.password)),
            )
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()
        s3.use { client ->
            client.createBucket { it.bucket(BUCKET) }
            val props = StorageProperties(
                backend = StorageProperties.Backend.S3,
                s3 = StorageProperties.S3(bucket = BUCKET),
            )
            val store = S3ArtifactStorage(client, props)
            val key = "com/example/widget/1.0.0/widget-1.0.0.jar"
            val bytes = Random.nextBytes(4096)

            store.write(key, bytes.inputStream())

            assertTrue(store.exists(key))
            assertArrayEquals(bytes, store.openRead(key)!!.stream.use { s -> s.readBytes() })
        }
    }

    companion object {
        private const val BUCKET = "relikquary"

        @Container
        @JvmStatic
        val minio: MinIOContainer = MinIOContainer("minio/minio:RELEASE.2024-01-16T16-07-38Z")
    }
}
