package org.khorum.oss.relikquary.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

/**
 * Guarded round-trip against the real Maven Central upstream (feature 006): proves the `maven-central`
 * proxy resolves and caches a real artifact end-to-end. **Auto-skipped when Central is unreachable**
 * (offline/CI without egress), mirroring the Docker-guarded MinIO test in feature 003 — so it adds
 * external-upstream realism where the network allows without making the suite flaky where it doesn't.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["relikquary.security.enabled=false"],
)
class ProxyCentralIT {

    @LocalServerPort
    var port: Int = 0

    private val http: HttpClient = HttpClient.newBuilder().proxy(ProxySelector.getDefault())
        .connectTimeout(PROBE_TIMEOUT).build()

    companion object {
        private val PROBE_TIMEOUT: Duration = Duration.ofSeconds(8)
        private const val CENTRAL = "https://repo1.maven.org/maven2"
        private const val ARTIFACT = "junit/junit/4.13.2/junit-4.13.2.pom"

        @TempDir
        @JvmStatic
        lateinit var storageRoot: Path

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            // Uses the default maven-central remoteUrl (real Central) — no override here.
            registry.add("relikquary.storage.filesystem.root") { storageRoot.toString() }
        }
    }

    @Test
    fun `proxies and caches a real artifact from Maven Central when reachable`() {
        assumeTrue(centralReachable()) { "Maven Central not reachable — skipping" }

        val first = get("/maven-central/$ARTIFACT")
        assertEquals(200, first.statusCode())
        assertTrue(first.body().isNotEmpty()) { "empty body from upstream" }

        // Cached locally; a second request returns the same bytes (served from cache).
        val cached = storageRoot.resolve("maven-central/$ARTIFACT")
        assertTrue(cached.toFile().isFile) { "artifact was not cached locally" }
        assertEquals(first.body().size, get("/maven-central/$ARTIFACT").body().size)
    }

    private fun get(path: String): HttpResponse<ByteArray> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

    // Any failure to reach Central (offline, blocked egress, timeout) means "skip", so the probe
    // intentionally treats every exception as unreachable.
    @Suppress("SwallowedException")
    private fun centralReachable(): Boolean = try {
        val probe = HttpRequest.newBuilder(URI.create("$CENTRAL/$ARTIFACT"))
            .timeout(PROBE_TIMEOUT).GET().build()
        http.send(probe, HttpResponse.BodyHandlers.discarding()).statusCode() == 200
    } catch (e: java.io.IOException) {
        false
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
}
