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
import java.nio.file.Files
import java.nio.file.Path

/**
 * Liveness/readiness probe behaviour (feature 010, FR-001/FR-002, SC-001). Probes are reachable without
 * credentials; readiness reflects storage reachability and flips to not-ready (503) when storage is gone,
 * recovering when it returns, while liveness is never affected. Also guards FR-009 (no secret leakage).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("observability")
class ProbesTest {

    @LocalServerPort
    private var port: Int = 0

    private val http: HttpClient = HttpClient.newHttpClient()

    private fun response(path: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `liveness and readiness are public and up`() {
        val liveness = response("/actuator/health/liveness")
        val readiness = response("/actuator/health/readiness")

        assertEquals(200, liveness.statusCode())
        assertTrue(liveness.body().contains("UP"))
        assertEquals(200, readiness.statusCode())
        assertTrue(readiness.body().contains("UP"))
    }

    @Test
    fun `readiness flips to not-ready when storage is gone and recovers`() {
        assertEquals(200, response("/actuator/health/readiness").statusCode())

        Files.delete(storageRoot) // simulate the storage backend becoming unreachable
        assertEquals(503, response("/actuator/health/readiness").statusCode())
        assertEquals(200, response("/actuator/health/liveness").statusCode()) // liveness unaffected

        Files.createDirectories(storageRoot) // restore storage
        assertEquals(200, response("/actuator/health/readiness").statusCode())
    }

    @Test
    fun `health output never contains configured secrets`() {
        val body = response("/actuator/health").body()

        for (secret in listOf("S3-LEAKCHECK-SECRET", "AKIA-LEAKCHECK-ACCESS", "upstream-leakcheck-pass", "upstream-leakcheck-user")) {
            assertFalse(body.contains(secret), "health output leaked: $secret")
        }
    }

    companion object {
        @TempDir
        @JvmStatic
        lateinit var storageRoot: Path

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("relikquary.storage.filesystem.root") { storageRoot.toString() }
            registry.add("RELIKQUARY_MAVEN_CENTRAL_URL") { "http://127.0.0.1:1" }
        }
    }
}
