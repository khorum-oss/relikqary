package org.khorum.oss.relikquary.integration

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * S3-backend parity for the streaming proxy cache (feature 015, US1/T012): a cold-cache proxy miss
 * streams the upstream bytes, commits the cache entry to S3 (via the pending-write tee), and serves the
 * second request from the S3 cache byte-identically. Runs against adobe/s3mock with `backend=s3`.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["relikquary.storage.backend=s3", "relikquary.security.enabled=false"],
)
class ProxyStreamingCacheS3IT {

    @LocalServerPort
    private var port: Int = 0

    private val http: HttpClient = HttpClient.newHttpClient()

    private fun getBytes(path: String): HttpResponse<ByteArray> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

    @Test
    fun `cold-cache miss streams and caches to S3, then serves the second from the S3 cache`() {
        val coord = "com/acme/s3stream/1.0.0/s3stream-1.0.0.jar"
        val bytes = ByteArray(8192) { (it % 256).toByte() }
        stub.seed(coord, bytes)

        val first = getBytes("/maven-central/$coord")
        assertEquals(200, first.statusCode())
        assertArrayEquals(bytes, first.body())

        stub.remove(coord)
        val second = getBytes("/maven-central/$coord")
        assertEquals(200, second.statusCode())
        assertArrayEquals(bytes, second.body())
    }

    companion object {
        private const val BUCKET = "relikquary"
        private val httpPort = freePort()
        private val stub = StubUpstream().start()
        private lateinit var process: Process

        @BeforeAll
        @JvmStatic
        fun startMock() {
            val jar = System.getProperty("relikquary.s3mockJar") ?: error("relikquary.s3mockJar not set")
            process = ProcessBuilder("java", "-jar", jar, "--server.port=${freePort()}", "--http.port=$httpPort")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            awaitPort(httpPort)
            S3Client.builder()
                .endpointOverride(URI.create("http://127.0.0.1:$httpPort"))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("foo", "bar")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build().use { it.createBucket { b -> b.bucket(BUCKET) } }
        }

        @AfterAll
        @JvmStatic
        fun stopMock() {
            stub.stop()
            if (::process.isInitialized) {
                process.destroy()
                process.waitFor(30, TimeUnit.SECONDS)
            }
        }

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("relikquary.storage.s3.endpoint") { "http://127.0.0.1:$httpPort" }
            registry.add("relikquary.storage.s3.region") { "us-east-1" }
            registry.add("relikquary.storage.s3.bucket") { BUCKET }
            registry.add("relikquary.storage.s3.access-key") { "foo" }
            registry.add("relikquary.storage.s3.secret-key") { "bar" }
            registry.add("relikquary.storage.s3.path-style-access") { true }
            registry.add("RELIKQUARY_MAVEN_CENTRAL_URL") { stub.baseUrl }
        }

        private fun freePort(): Int = ServerSocket(0).use { it.localPort }

        @Suppress("SwallowedException")
        private fun awaitPort(port: Int) {
            val deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos()
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
