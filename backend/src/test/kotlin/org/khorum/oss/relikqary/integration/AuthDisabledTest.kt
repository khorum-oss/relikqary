package org.khorum.oss.relikqary.integration

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
 * Local-dev opt-out (feature 002, FR-007/SC-003): with `relikqary.security.enabled=false`, publishing
 * needs no credentials.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["relikqary.security.enabled=false"],
)
class AuthDisabledTest {

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
            registry.add("relikqary.storage.filesystem.root") { storageRoot.toString() }
        }
    }

    @Test
    fun `publish without credentials succeeds when auth is disabled`() {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/com/example/local/1.0.0/local-1.0.0.jar"))
            .header("Content-Type", "application/octet-stream")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(Random.nextBytes(128)))
            .build()
        assertEquals(201, http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode())
    }
}
