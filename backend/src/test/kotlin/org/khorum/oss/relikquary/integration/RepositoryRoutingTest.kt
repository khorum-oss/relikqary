package org.khorum.oss.relikquary.integration

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
import kotlin.random.Random

/**
 * Named-repository routing and per-type policy (feature 004, contracts/named-repositories.md), against
 * the default `releases` (release) and `snapshots` (snapshot) repos. Auth is disabled to keep the
 * focus on routing.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["relikquary.security.enabled=false"],
)
class RepositoryRoutingTest {

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
            registry.add("relikquary.storage.filesystem.root") { storageRoot.toString() }
        }
    }

    private fun put(path: String, body: ByteArray = Random.nextBytes(64)): Int {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/octet-stream")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    private fun get(path: String): Int {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build()
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    @Test
    fun `release repo stores a release then rejects re-publish and serves it`() {
        val path = "/releases/com/example/widget/1.0.0/widget-1.0.0.jar"
        assertEquals(201, put(path))
        assertEquals(409, put(path))
        assertEquals(200, get(path))
    }

    @Test
    fun `release repo rejects a snapshot coordinate`() {
        assertEquals(400, put("/releases/com/example/widget/2.0.0-SNAPSHOT/widget-2.0.0-SNAPSHOT.jar"))
    }

    @Test
    fun `snapshot repo rejects a release coordinate`() {
        assertEquals(400, put("/snapshots/com/example/widget/3.0.0/widget-3.0.0.jar"))
    }

    @Test
    fun `snapshot repo overwrites an existing snapshot`() {
        val path = "/snapshots/com/example/widget/4.0.0-SNAPSHOT/widget-4.0.0-SNAPSHOT.jar"
        assertEquals(201, put(path))
        assertEquals(200, put(path))
    }

    @Test
    fun `unknown repository returns 404`() {
        assertEquals(404, get("/nope/com/example/widget/1.0.0/widget-1.0.0.jar"))
        assertEquals(404, put("/nope/com/example/widget/1.0.0/widget-1.0.0.jar"))
    }

    @Test
    fun `coordinates are isolated per repository`() {
        val snap = "com/example/iso/5.0.0-SNAPSHOT/iso-5.0.0-SNAPSHOT.jar"
        assertEquals(201, put("/snapshots/$snap"))
        // The same artifact path under a different repo is a different object.
        assertEquals(404, get("/releases/$snap"))
    }
}
