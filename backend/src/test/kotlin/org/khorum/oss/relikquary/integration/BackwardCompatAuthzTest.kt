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
import java.util.Base64
import kotlin.random.Random

/**
 * Regression: with the default config (no `access` blocks), behaviour matches features 002/004 — open
 * reads, global-`PUBLISH`-gated writes — and invalid paths still return `400` (FR-011).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "relikquary.security.enabled=true",
        "relikquary.security.users[0].username=ci",
        "relikquary.security.users[0].password={noop}secret",
        "relikquary.security.users[0].roles[0]=PUBLISH",
        "relikquary.security.users[1].username=reader",
        "relikquary.security.users[1].password={noop}secret",
        "relikquary.security.users[1].roles[0]=READER",
    ],
)
class BackwardCompatAuthzTest {

    @LocalServerPort
    var port: Int = 0
    private val http: HttpClient = HttpClient.newHttpClient()

    companion object {
        @TempDir
        @JvmStatic
        lateinit var storageRoot: Path

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("relikquary.storage.filesystem.root") { storageRoot.toString() }
        }
    }

    private fun basic(u: String, p: String) = "Basic " + Base64.getEncoder().encodeToString("$u:$p".toByteArray())

    private fun put(path: String, auth: String? = null): Int {
        val b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(Random.nextBytes(32)))
        auth?.let { b.header("Authorization", it) }
        return http.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    private fun get(path: String): Int =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.discarding(),
        ).statusCode()

    @Test
    fun `open read, publish gated by global PUBLISH (unchanged)`() {
        val path = "/releases/com/example/legacy/1.0.0/legacy-1.0.0.jar"
        assertEquals(401, put(path))
        assertEquals(403, put(path, basic("reader", "secret")))
        assertEquals(201, put(path, basic("ci", "secret")))
        assertEquals(200, get(path)) // read stays open
    }

    @Test
    fun `invalid traversal path is rejected with 400`() {
        assertEquals(400, get("/releases/com/example/../bad/1.0.0/bad-1.0.0.jar"))
    }
}
