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
 * The default `public` group (feature 006) aggregates `releases` (hosted) and `maven-central` (proxy).
 * One URL serves both a first-party artifact (from the hosted member) and a third-party dependency (via
 * the proxy member, pointed at a [StubUpstream]); first-match wins; absent everywhere → 404; group is
 * read-only (405).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["relikquary.security.enabled=false"],
)
class GroupResolveTest {

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

    private fun get(path: String): HttpResponse<ByteArray> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

    private fun put(path: String, body: ByteArray): Int =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
            HttpResponse.BodyHandlers.discarding(),
        ).statusCode()

    @Test
    fun `group serves a first-party artifact from the hosted member`() {
        val coord = "com/example/widget/1.0.0/widget-1.0.0.jar"
        val bytes = "first-party".toByteArray()
        assertEquals(201, put("/releases/$coord", bytes))

        val viaGroup = get("/public/$coord")
        assertEquals(200, viaGroup.statusCode())
        assertArrayEquals(bytes, viaGroup.body())
    }

    @Test
    fun `group serves a third-party dependency via the proxy member`() {
        val coord = "org/thirdparty/dep/2.0.0/dep-2.0.0.jar"
        val bytes = "from-upstream".toByteArray()
        stub.seed(coord, bytes)

        val viaGroup = get("/public/$coord")
        assertEquals(200, viaGroup.statusCode())
        assertArrayEquals(bytes, viaGroup.body())
    }

    @Test
    fun `group returns 404 when no member has the artifact`() {
        assertEquals(404, get("/public/org/nowhere/x/1.0.0/x-1.0.0.jar").statusCode())
    }

    @Test
    fun `publishing to a group is rejected with 405`() {
        assertEquals(405, put("/public/com/example/widget/1.0.0/widget-1.0.0.jar", byteArrayOf(1)))
    }
}
