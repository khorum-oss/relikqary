package org.khorum.oss.relikquary.integration

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.khorum.oss.relikquary.config.StorageProperties
import org.khorum.oss.relikquary.storage.S3ArtifactStorage
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI

/**
 * S3 storage-probe parity (feature 010, FR-008), verified against adobe/s3mock as an external process.
 * A reachable bucket probes healthy; an absent bucket probes unhealthy — the same readiness signal the
 * filesystem backend provides, adapted to S3 via headBucket (no object is written by a probe).
 */
class S3StorageProbeTest {

    private fun storage(bucket: String) =
        S3ArtifactStorage(s3, StorageProperties(backend = StorageProperties.Backend.S3, s3 = StorageProperties.S3(bucket = bucket)))

    @Test
    fun `probe is healthy against a reachable bucket`() {
        val probe = storage(BUCKET).probe()

        assertTrue(probe.healthy)
        assertEquals("s3", probe.backend)
    }

    @Test
    fun `probe is unhealthy against a missing bucket`() {
        val probe = storage("no-such-bucket").probe()

        assertFalse(probe.healthy)
        assertEquals("s3", probe.backend)
        assertNotNull(probe.detail)
    }

    companion object {
        private const val BUCKET = "relikquary"
        private lateinit var process: Process
        private lateinit var s3: S3Client

        @BeforeAll
        @JvmStatic
        fun startMock() {
            val jar = System.getProperty("relikquary.s3mockJar")
                ?: error("relikquary.s3mockJar system property not set")
            val httpPort = freePort()
            val httpsPort = freePort()
            process = ProcessBuilder(
                "java", "-jar", jar, "--server.port=$httpsPort", "--http.port=$httpPort",
            ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            awaitPort(httpPort)

            s3 = S3Client.builder()
                .endpointOverride(URI.create("http://127.0.0.1:$httpPort"))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("foo", "bar")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()
            s3.createBucket { it.bucket(BUCKET) }
        }

        @AfterAll
        @JvmStatic
        fun stopMock() {
            if (::s3.isInitialized) s3.close()
            if (::process.isInitialized) {
                process.destroy()
                process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            }
        }

        private fun freePort(): Int = ServerSocket(0).use { it.localPort }

        @Suppress("SwallowedException")
        private fun awaitPort(port: Int) {
            val deadline = System.nanoTime() + java.time.Duration.ofSeconds(45).toNanos()
            while (System.nanoTime() < deadline) {
                try {
                    Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
                    return
                } catch (e: Exception) {
                    Thread.sleep(250)
                }
            }
            error("s3mock did not start listening on port $port")
        }
    }
}
