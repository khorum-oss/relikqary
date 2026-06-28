package org.khorum.oss.relikquary.integration

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
import kotlin.random.Random

/**
 * Real S3 protocol round-trip for [S3ArtifactStorage], verified against adobe/s3mock run as an
 * external process (the runnable `exec` jar in its own JVM — no Docker, no Spring Boot classpath
 * clash). Proves put/get/exists are byte-for-byte and that an absent key reads as null (FR-004/006).
 */
class S3RoundTripTest {

    private fun storage() =
        S3ArtifactStorage(s3, StorageProperties(backend = StorageProperties.Backend.S3, s3 = StorageProperties.S3(bucket = BUCKET)))

    @Test
    fun `stores and serves an object byte-for-byte`() {
        val store = storage()
        val key = "com/example/widget/1.0.0/widget-1.0.0.jar"
        val bytes = Random.nextBytes(8192)

        val written = store.write(key, bytes.inputStream())

        assertEquals(bytes.size.toLong(), written)
        assertTrue(store.exists(key))
        val read = store.openRead(key)!!
        assertEquals(bytes.size.toLong(), read.sizeBytes)
        assertArrayEquals(bytes, read.stream.use { it.readBytes() })
    }

    @Test
    fun `reports an absent key as missing`() {
        val store = storage()
        assertFalse(store.exists("com/example/missing/9.9.9/missing-9.9.9.jar"))
        assertNull(store.openRead("com/example/missing/9.9.9/missing-9.9.9.jar"))
    }

    @Test
    fun `lists folders and files then deletes a prefix`() {
        val store = storage()
        store.write("releases/com/example/lib/1.0.0/lib-1.0.0.jar", byteArrayOf(1, 2, 3).inputStream())
        store.write("releases/com/example/lib/1.0.0/lib-1.0.0.pom", byteArrayOf(4, 5).inputStream())
        store.write("releases/com/example/lib/2.0.0/lib-2.0.0.jar", byteArrayOf(6).inputStream())

        val versions = store.list("releases/com/example/lib").filter { it.isDirectory }.map { it.name }
        org.junit.jupiter.api.Assertions.assertEquals(listOf("1.0.0", "2.0.0"), versions.sorted())

        val files = store.list("releases/com/example/lib/1.0.0")
        org.junit.jupiter.api.Assertions.assertEquals(setOf("lib-1.0.0.jar", "lib-1.0.0.pom"), files.map { it.name }.toSet())
        assertTrue(files.all { !it.isDirectory && it.sizeBytes != null })

        org.junit.jupiter.api.Assertions.assertEquals(2, store.deletePrefix("releases/com/example/lib/1.0.0"))
        assertFalse(store.exists("releases/com/example/lib/1.0.0/lib-1.0.0.jar"))
        assertTrue(store.exists("releases/com/example/lib/2.0.0/lib-2.0.0.jar"))
    }

    @Test
    fun `walk recursively enumerates files with keys, sizes, and last-modified (feature 009 parity)`() {
        val store = storage()
        store.write("walk/com/acme/w/1.0.0-SNAPSHOT/w-1.jar", byteArrayOf(1, 2, 3).inputStream())
        store.write("walk/com/acme/w/1.0.0-SNAPSHOT/w-2.jar", byteArrayOf(4).inputStream())

        val walked = store.walk("walk/com/acme/w/").associateBy { it.key }
        assertEquals(
            setOf("walk/com/acme/w/1.0.0-SNAPSHOT/w-1.jar", "walk/com/acme/w/1.0.0-SNAPSHOT/w-2.jar"),
            walked.keys,
        )
        assertEquals(3L, walked.getValue("walk/com/acme/w/1.0.0-SNAPSHOT/w-1.jar").sizeBytes)
        assertTrue(walked.values.all { it.lastModified != null })
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
