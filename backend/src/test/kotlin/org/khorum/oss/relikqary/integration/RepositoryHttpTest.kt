package org.khorum.oss.relikqary.integration

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import kotlin.random.Random

/**
 * HTTP-level behaviour of the repository protocol (contracts/repository-http.md), exercised through a
 * real servlet container with a real filesystem store wired via [DynamicPropertySource]. Uses the JDK
 * HTTP client so raw request paths (e.g. encoded traversal) reach the server verbatim.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // This suite exercises the repository protocol itself; authentication is covered separately
    // in AuthPublishTest, so run with auth disabled to keep these cases focused.
    properties = ["relikqary.security.enabled=false"],
)
class RepositoryHttpTest {

    @LocalServerPort
    var port: Int = 0

    private val http: HttpClient = HttpClient.newHttpClient()

    companion object {
        @TempDir
        @JvmStatic
        lateinit var storageRoot: Path

        @DynamicPropertySource
        @JvmStatic
        fun storageProps(registry: DynamicPropertyRegistry) {
            registry.add("relikqary.storage.filesystem.root") { storageRoot.toString() }
        }
    }

    private fun base() = "http://127.0.0.1:$port"

    private fun put(path: String, body: ByteArray): Int {
        val request = HttpRequest.newBuilder(URI.create("${base()}$path"))
            .header("Content-Type", "application/octet-stream")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    private fun get(path: String): HttpResponse<ByteArray> =
        http.send(HttpRequest.newBuilder(URI.create("${base()}$path")).GET().build(), HttpResponse.BodyHandlers.ofByteArray())

    @Test
    fun `publishes and resolves a file byte-for-byte`() {
        val body = Random.nextBytes(2048)
        val path = "/com/example/widget/3.0.0/widget-3.0.0.jar"

        assertEquals(201, put(path, body))

        val response = get(path)
        assertEquals(200, response.statusCode())
        assertArrayEquals(body, response.body())
    }

    @Test
    fun `returns 404 for an unpublished coordinate`() {
        assertEquals(404, get("/com/example/widget/9.9.9/widget-9.9.9.jar").statusCode())
    }

    @Test
    fun `rejects re-publishing an existing release with 409`() {
        val path = "/com/example/locked/1.0.0/locked-1.0.0.jar"
        assertEquals(201, put(path, Random.nextBytes(64)))
        assertEquals(409, put(path, Random.nextBytes(64)))
    }

    @Test
    fun `allows overwriting an existing snapshot`() {
        val path = "/com/example/snap/1.0.0-SNAPSHOT/snap-1.0.0-SNAPSHOT.jar"
        val second = Random.nextBytes(128)
        assertEquals(201, put(path, Random.nextBytes(128)))
        assertEquals(200, put(path, second))
        assertArrayEquals(second, get(path).body())
    }

    @Test
    fun `rejects path traversal attempts`() {
        // An encoded traversal must never resolve outside the storage root (FR-012, SC-008).
        val request = HttpRequest.newBuilder(URI.create("${base()}/com/example/%2e%2e%2f%2e%2e%2fescape.jar")).GET().build()
        val status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
        assertTrue(status in 400..499, "expected 4xx, got $status")
    }
}
