package org.khorum.oss.relikquary.integration

import com.fasterxml.jackson.databind.ObjectMapper
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

/**
 * Dashboard stats feed (feature 016, Phase 2). The figures come from the shared storage-usage snapshot;
 * a 0s refresh makes each request recompute so the assertions are deterministic against freshly seeded
 * content. Auth disabled to focus on the aggregation.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "relikquary.security.enabled=false",
        "relikquary.observability.storage-usage-refresh=0s",
    ],
)
class StatsApiTest {

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

    @Test
    fun `stats reports repository count, object count, and bytes used`() {
        // Two files (10 bytes total) into a hosted repo.
        assertEquals(201, put("/releases/com/example/stats/1.0.0/stats-1.0.0.jar", ByteArray(5)))
        assertEquals(201, put("/releases/com/example/stats/1.0.0/stats-1.0.0.pom", ByteArray(5)))

        val stats = json.readTree(get("/api/stats").body())
        // All configured repositories (releases, snapshots, maven-central, gradle-plugins, public, plugins).
        assertEquals(6, stats["repositories"].asInt())
        assertTrue(stats["artifacts"].asLong() >= 2) { "object count should include the seeded files: $stats" }
        assertTrue(stats["storageBytes"].asLong() >= 10) { "bytes should include the seeded files: $stats" }
    }
}
