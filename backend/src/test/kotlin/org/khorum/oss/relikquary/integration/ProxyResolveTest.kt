package org.khorum.oss.relikquary.integration

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
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

/**
 * HTTP-level behaviour of the default `maven-central` proxy (feature 006), pointed at a local
 * [StubUpstream] instead of the real Maven Central. Covers cache miss→hit, serving from cache when the
 * upstream no longer has the artifact, metadata pass-through, 404 vs 502, and read-only 405.
 *
 * The default `maven-central` proxy's `remoteUrl` resolves the `RELIKQUARY_MAVEN_CENTRAL_URL`
 * placeholder, overridden here to the stub.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["relikquary.security.enabled=false"],
)
class ProxyResolveTest {

    @LocalServerPort
    var port: Int = 0

    private val http: HttpClient = HttpClient.newHttpClient()

    companion object {
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

    private fun get(path: String): HttpResponse<ByteArray> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build()
        return http.send(request, HttpResponse.BodyHandlers.ofByteArray())
    }

    private fun put(path: String): Int {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(byteArrayOf(1))).build()
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    @Test
    fun `cache miss fetches from upstream then serves from cache when upstream drops it`() {
        val coord = "com/acme/lib/1.0.0/lib-1.0.0.jar"
        val bytes = "real-upstream-bytes".toByteArray()
        stub.seed(coord, bytes)

        val first = get("/maven-central/$coord")
        assertEquals(200, first.statusCode())
        assertArrayEquals(bytes, first.body())

        // Upstream no longer has it; a cached proxy must still serve it without contacting upstream.
        stub.remove(coord)
        val second = get("/maven-central/$coord")
        assertEquals(200, second.statusCode())
        assertArrayEquals(bytes, second.body())
    }

    @Test
    fun `maven-metadata is pass-through and not cached`() {
        val meta = "com/acme/lib/maven-metadata.xml"
        stub.seed(meta, "<metadata/>".toByteArray())
        assertEquals(200, get("/maven-central/$meta").statusCode())

        // Pass-through means it is never cached: once the upstream drops it, the proxy returns 404.
        stub.remove(meta)
        assertEquals(404, get("/maven-central/$meta").statusCode())
    }

    @Test
    fun `unknown upstream coordinate returns 404`() {
        assertEquals(404, get("/maven-central/com/acme/missing/9.9.9/missing-9.9.9.jar").statusCode())
    }

    @Test
    fun `upstream error on a cold miss returns 502`() {
        val coord = "com/acme/broken/1.0.0/broken-1.0.0.jar"
        stub.fail(coord)
        assertEquals(502, get("/maven-central/$coord").statusCode())
    }

    @Test
    fun `publishing to a proxy is rejected with 405`() {
        assertEquals(405, put("/maven-central/com/acme/lib/1.0.0/lib-1.0.0.jar"))
    }
}
