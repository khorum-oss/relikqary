package org.khorum.oss.relikquary.integration

import org.junit.jupiter.api.Assertions.assertEquals
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
 * Operator gating of the management surface (feature 010, FR-006/SC-004). With security enabled the
 * liveness/readiness probes are public, while detailed health, metrics, and the Prometheus scrape
 * require the global PUBLISH role (401 anon, 403 authenticated-non-publisher, 200 publisher).
 */
class OperationalAuthTest : AbstractAuthzTest() {

    @Test
    fun `probes are reachable without credentials`() {
        assertEquals(200, get("/actuator/health/liveness").statusCode())
        assertEquals(200, get("/actuator/health/readiness").statusCode())
    }

    @Test
    fun `metrics and detailed health require the PUBLISH role`() {
        for (path in listOf("/actuator/prometheus", "/actuator/metrics", "/actuator/health")) {
            assertEquals(401, get(path).statusCode(), "$path anonymous")
            assertEquals(403, get(path, basic("bob", "pw")).statusCode(), "$path non-publisher")
            assertEquals(200, get(path, basic("ci", "pw")).statusCode(), "$path publisher")
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
        }
    }
}

/**
 * With security disabled every operational endpoint is open (local-dev parity, FR-006). Uses the
 * observability profile; the proxy points at a dead port so the upstream health check resolves quickly
 * to DEGRADED (still HTTP 200) without real network access.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("observability")
class OperationalAuthDisabledTest {

    @LocalServerPort
    private var port: Int = 0

    private val http: HttpClient = HttpClient.newHttpClient()

    private fun code(path: String): Int {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build()
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    @Test
    fun `all operational endpoints are open when security is disabled`() {
        assertEquals(200, code("/actuator/health/liveness"))
        assertEquals(200, code("/actuator/health/readiness"))
        assertEquals(200, code("/actuator/prometheus"))
        assertEquals(200, code("/actuator/health"))
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
