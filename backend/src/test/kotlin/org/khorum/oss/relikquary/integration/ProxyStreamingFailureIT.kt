package org.khorum.oss.relikquary.integration

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.khorum.oss.relikquary.storage.ArtifactStorage
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path

/**
 * Streaming proxy cache, failure paths (feature 015, US2): a client disconnect or an upstream
 * truncation mid-transfer must never promote a partial artifact into the cache, and a later request
 * must still resolve cleanly.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["relikquary.security.enabled=false"],
)
class ProxyStreamingFailureIT {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var storage: ArtifactStorage

    private val http: HttpClient = HttpClient.newHttpClient()

    companion object {
        private const val SETTLE_MILLIS = 300L
        private val stub = StubUpstream().start()

        @TempDir
        @JvmStatic
        lateinit var storageRoot: Path

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("relikquary.storage.filesystem.root") { storageRoot.toString() }
            registry.add("RELIKQUARY_MAVEN_CENTRAL_URL") { stub.baseUrl }
        }
    }

    @Test
    fun `a client that disconnects mid-transfer leaves nothing cached`() {
        val coord = "com/acme/dropped/1.0.0/dropped-1.0.0.jar"
        val bytes = ByteArray(1 shl 20) { (it % 256).toByte() } // 1 MiB so the transfer can't complete fast
        stub.seed(coord, bytes)
        val cacheKey = "maven-central/$coord"

        val response = http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/maven-central/$coord")).GET().build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        // Read a small prefix, then disconnect by closing the body stream before EOF.
        response.body().use { it.read(ByteArray(256)) }

        // EOF was never reached, so the tee can never commit: the cache key must not exist.
        Thread.sleep(SETTLE_MILLIS)
        assertFalse(storage.exists(cacheKey), "a disconnected transfer must not be cached")

        // And a fresh request still resolves cleanly.
        val retry = http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/maven-central/$coord")).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        assertEquals(200, retry.statusCode())
        assertArrayEquals(bytes, retry.body())
    }

    @Test
    fun `an upstream truncation mid-body leaves nothing cached and a retry succeeds`() {
        val coord = "com/acme/truncated/1.0.0/truncated-1.0.0.jar"
        val cacheKey = "maven-central/$coord"
        // Declare 2048 bytes but only ever send 512, then close: a truncated fixed-length body.
        stub.seedTruncated(coord, declaredLength = 2048, partial = ByteArray(512) { 'X'.code.toByte() })

        runCatching {
            http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/maven-central/$coord")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
        } // the client may see a truncated/failed body — the point is what gets cached

        assertFalse(storage.exists(cacheKey), "a truncated upstream transfer must not be cached")

        // Upstream recovers; a retry must fetch the full, correct artifact (not a cached partial).
        val full = ByteArray(2048) { (it % 256).toByte() }
        stub.seed(coord, full)
        val retry = http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/maven-central/$coord")).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        assertEquals(200, retry.statusCode())
        assertArrayEquals(full, retry.body())
    }
}
