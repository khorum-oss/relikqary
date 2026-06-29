package org.khorum.oss.relikquary.integration

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import java.nio.file.Files
import java.nio.file.Path

/**
 * Feature 012: HTTP-level behaviour of the default `gradle-plugins` proxy, pointed at a local
 * [StubUpstream] instead of the real Gradle Plugin Portal (offline/CI-safe). The `gradle-plugins`
 * proxy reuses the feature-006 proxy code path; this confirms it is wired correctly for the new repo,
 * covering cache miss→hit (US2), metadata pass-through (FR-006), and 404 vs 502 (FR-008/SC-006).
 *
 * The default `gradle-plugins` proxy's `remoteUrl` resolves the `RELIKQUARY_GRADLE_PLUGIN_PORTAL_URL`
 * placeholder, overridden here to the stub.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["relikquary.security.enabled=false"],
)
class GradlePluginsProxyResolveTest {

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
            registry.add("RELIKQUARY_GRADLE_PLUGIN_PORTAL_URL") { stub.baseUrl }
        }
    }

    // A plugin marker POM coordinate, exactly as Gradle requests it from a Maven-layout plugin repo.
    private val markerCoord =
        "com/example/demo/com.example.demo.gradle.plugin/1.0.0/com.example.demo.gradle.plugin-1.0.0.pom"

    @Test
    fun `cache miss fetches the plugin marker then serves it from cache without upstream`() {
        val bytes = "<project><!-- plugin marker --></project>".toByteArray()
        stub.seed(markerCoord, bytes)

        val first = get("/gradle-plugins/$markerCoord")
        assertEquals(200, first.statusCode())
        assertArrayEquals(bytes, first.body())

        // Cached under the proxy's own namespace; with the upstream dropped, the cache still serves it.
        stub.remove(markerCoord)
        val cached = get("/gradle-plugins/$markerCoord")
        assertEquals(200, cached.statusCode())
        assertArrayEquals(bytes, cached.body())
    }

    @Test
    fun `plugin metadata is pass-through and never cached`() {
        val meta = "com/example/demo/com.example.demo.gradle.plugin/maven-metadata.xml"
        stub.seed(meta, "<metadata><versioning/></metadata>".toByteArray())
        assertEquals(200, get("/gradle-plugins/$meta").statusCode())

        // Pass-through means it is never written to the cache…
        assertFalse(Files.exists(storageRoot.resolve("gradle-plugins/$meta"))) { "metadata must not be cached" }
        // …and once the upstream drops it, the proxy returns 404 (no stale cached copy served).
        stub.remove(meta)
        assertEquals(404, get("/gradle-plugins/$meta").statusCode())
    }

    @Test
    fun `unknown plugin coordinate returns 404 and caches nothing`() {
        val missing = "com/example/missing/com.example.missing.gradle.plugin/9.9.9/com.example.missing.gradle.plugin-9.9.9.pom"
        assertEquals(404, get("/gradle-plugins/$missing").statusCode())
        assertFalse(Files.exists(storageRoot.resolve("gradle-plugins/$missing"))) { "404 must not cache" }
    }

    @Test
    fun `upstream error on a cold miss returns 502, distinct from a 404`() {
        val coord = "com/example/broken/com.example.broken.gradle.plugin/1.0.0/com.example.broken.gradle.plugin-1.0.0.pom"
        stub.fail(coord)
        assertEquals(502, get("/gradle-plugins/$coord").statusCode())
    }

    @Test
    fun `publishing to the plugin proxy is rejected with 405`() {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/gradle-plugins/$markerCoord"))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(byteArrayOf(1))).build()
        assertEquals(405, http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode())
    }

    private fun get(path: String): HttpResponse<ByteArray> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
}
