package org.khorum.oss.relikquary.integration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

/**
 * Cross-repo artifact catalog (feature 016, Phase 2): one entry per `group:artifact` with the correct
 * latest version, version count, and aggregate size; `q` filters by coordinate; paging bounds results.
 * Auth disabled to focus on the aggregation (the read-scoping is exercised by the authorizer elsewhere).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["relikquary.security.enabled=false"],
)
class CatalogApiTest {

    @LocalServerPort
    var port: Int = 0

    private val http: HttpClient = HttpClient.newHttpClient()
    private val json = ObjectMapper()

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

    private fun url(path: String) = URI.create("http://127.0.0.1:$port$path")

    private fun put(path: String, body: ByteArray): Int {
        val req = HttpRequest.newBuilder(url(path)).header("Content-Type", "application/octet-stream")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build()
        return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    private fun get(path: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(url(path)).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun entries(query: String): List<JsonNode> =
        json.readTree(get("/api/catalog$query").body())["entries"].toList()

    // The static storage root is shared across methods and release coordinates are immutable, so each
    // test seeds distinct artifact names.
    private fun seed(group: String, artifact: String, version: String, vararg sizes: Int) {
        val groupPath = group.replace('.', '/')
        val exts = listOf("jar", "pom", "module")
        sizes.forEachIndexed { i, size ->
            val ext = exts[i]
            val key = "/releases/$groupPath/$artifact/$version/$artifact-$version.$ext"
            assertEquals(201, put(key, ByteArray(size))) { "seeding $key" }
        }
    }

    @Test
    fun `one entry per coordinate with latest, count, and size`() {
        seed("com.example", "widgetcount", "1.0.0", 3, 2) // jar + pom = 5 bytes
        seed("com.example", "widgetcount", "2.0.0", 4) // jar = 4 bytes
        seed("org.acme", "libcount", "1.0.0", 6)

        val widget = entries("?repo=releases").first { it["artifact"].asText() == "widgetcount" }
        assertEquals("com.example", widget["group"].asText())
        assertEquals("2.0.0", widget["latestVersion"].asText())
        assertEquals(2, widget["versionCount"].asInt())
        assertEquals(9, widget["sizeBytes"].asLong()) // 3 + 2 + 4

        val lib = entries("?repo=releases").first { it["artifact"].asText() == "libcount" }
        assertEquals("org.acme", lib["group"].asText())
        assertEquals(1, lib["versionCount"].asInt())
        assertEquals(6, lib["sizeBytes"].asLong())
    }

    @Test
    fun `q filters by coordinate fragment`() {
        seed("com.example", "qwidget", "1.0.0", 3)
        seed("com.example", "qgadget", "1.0.0", 3)

        val matches = entries("?repo=releases&q=qwidget")
        assertTrue(matches.isNotEmpty())
        assertTrue(matches.all { it["artifact"].asText() == "qwidget" }) { "q=qwidget returned $matches" }
        assertNull(matches.firstOrNull { it["artifact"].asText() == "qgadget" })
    }

    @Test
    fun `paging bounds the result and discloses truncation`() {
        seed("com.example", "pageone", "1.0.0", 3)
        seed("com.example", "pagetwo", "1.0.0", 3)

        val body = json.readTree(get("/api/catalog?repo=releases&pageSize=1&page=0").body())
        assertEquals(1, body["entries"].size())
        assertEquals(1, body["pageSize"].asInt())
        assertTrue(body["total"].asLong() >= 2) { "expected at least two coordinates: $body" }
        assertTrue(body["truncated"].asBoolean()) { "first of many pages should be truncated: $body" }
    }
}
