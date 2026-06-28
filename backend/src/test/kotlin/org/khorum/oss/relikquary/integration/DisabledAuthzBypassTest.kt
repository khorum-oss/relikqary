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
 * US3: with security globally disabled, per-repository rules are ignored — even the private `privlib`
 * repo is fully open (FR-009). Uses the `authz` topology but overrides `enabled=false`.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["relikquary.security.enabled=false"],
)
@ActiveProfiles("authz")
class DisabledAuthzBypassTest {

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

    @Test
    fun `private repo is open when security is disabled`() {
        val path = "/privlib/com/acme/z/1.0.0/z-1.0.0.jar"
        val putReq = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(byteArrayOf(1, 2, 3))).build()
        assertEquals(201, http.send(putReq, HttpResponse.BodyHandlers.discarding()).statusCode())

        val getReq = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build()
        assertEquals(200, http.send(getReq, HttpResponse.BodyHandlers.discarding()).statusCode())
    }
}
