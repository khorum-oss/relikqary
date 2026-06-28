package org.khorum.oss.relikquary.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path

/**
 * Drives real traffic (publish, resolve, proxy miss→hit, cleanup) and scrapes `/actuator/prometheus`,
 * confirming the HTTP, publish/resolve, proxy cache/upstream, cleanup, and storage-usage meters are
 * present and reflect the traffic (feature 010, FR-004, SC-002). Also guards FR-009: no configured
 * secret appears in the scrape.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("observability")
class MetricsScrapeTest {

    @LocalServerPort
    private var port: Int = 0

    private val http: HttpClient = HttpClient.newHttpClient()

    private fun uri(path: String) = URI.create("http://127.0.0.1:$port$path")

    private fun put(path: String, body: ByteArray): Int =
        http.send(
            HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
            HttpResponse.BodyHandlers.discarding(),
        ).statusCode()

    private fun get(path: String): Int =
        http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofByteArray()).statusCode()

    private fun post(path: String): Int =
        http.send(
            HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.discarding(),
        ).statusCode()

    private fun scrape(): String =
        http.send(HttpRequest.newBuilder(uri("/actuator/prometheus")).GET().build(), HttpResponse.BodyHandlers.ofString())
            .body()

    @Test
    fun `the metric set reflects driven traffic`() {
        stub.seed("com/acme/dep/1.0/dep-1.0.jar", byteArrayOf(1, 2, 3, 4))

        // Publish + resolve a hosted artifact.
        assertEquals(201, put("/releases/com/acme/lib/1.0/lib-1.0.jar", byteArrayOf(9, 8, 7)))
        assertEquals(200, get("/releases/com/acme/lib/1.0/lib-1.0.jar"))
        // Proxy cache miss (fetches + caches upstream) then hit.
        assertEquals(200, get("/maven-central/com/acme/dep/1.0/dep-1.0.jar"))
        assertEquals(200, get("/maven-central/com/acme/dep/1.0/dep-1.0.jar"))
        // A cleanup run.
        assertEquals(200, post("/api/cleanup"))

        val body = scrape()

        assertTrue(body.contains("http_server_requests_seconds"), "HTTP request metrics present")
        assertTrue(body.contains("relikquary_publish_total"), "publish counter present")
        assertTrue(body.contains("relikquary_resolve_total"), "resolve counter present")
        assertTrue(body.contains("relikquary_proxy_cache_total"), "proxy cache counter present")
        assertTrue(body.contains("result=\"hit\""), "cache hit recorded")
        assertTrue(body.contains("result=\"miss\""), "cache miss recorded")
        assertTrue(body.contains("relikquary_proxy_upstream_total"), "upstream counter present")
        assertTrue(body.contains("relikquary_cleanup_items_removed_total"), "cleanup items counter present")
        assertTrue(body.contains("relikquary_cleanup_bytes_reclaimed_total"), "cleanup bytes counter present")
        assertTrue(body.contains("relikquary_storage_usage_bytes"), "storage usage gauge present")

        for (secret in listOf("S3-LEAKCHECK-SECRET", "AKIA-LEAKCHECK-ACCESS", "upstream-leakcheck-pass", "upstream-leakcheck-user")) {
            assertFalse(body.contains(secret), "metrics output leaked: $secret")
        }
    }

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
}
