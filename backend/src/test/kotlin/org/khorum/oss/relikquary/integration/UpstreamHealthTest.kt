package org.khorum.oss.relikquary.integration

import com.fasterxml.jackson.databind.ObjectMapper
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
 * Proxy-upstream health is detail-only (feature 010, FR-003, SC-005): an unreachable upstream shows as
 * DEGRADED in the detailed health view (HTTP 200) but never fails liveness or readiness — a transient
 * upstream outage must not take the instance out of service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("observability")
class UpstreamHealthTest {

    @LocalServerPort
    private var port: Int = 0

    private val http: HttpClient = HttpClient.newHttpClient()
    private val mapper = ObjectMapper()

    private fun response(path: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `an unreachable upstream degrades health without failing the probes`() {
        stub.stop() // the maven-central upstream is now unreachable

        val health = response("/actuator/health")
        assertEquals(200, health.statusCode()) // DEGRADED maps to 200, not 503
        val tree = mapper.readTree(health.body())
        assertEquals("DEGRADED", tree.path("status").asText())
        assertEquals("DEGRADED", tree.path("components").path("upstreams").path("status").asText())

        // Liveness and readiness remain healthy.
        assertEquals(200, response("/actuator/health/readiness").statusCode())
        assertEquals(200, response("/actuator/health/liveness").statusCode())
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
