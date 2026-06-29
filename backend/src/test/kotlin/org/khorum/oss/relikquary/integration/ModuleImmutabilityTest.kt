package org.khorum.oss.relikquary.integration

import org.junit.jupiter.api.Assertions.assertArrayEquals
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
import java.util.Base64

/**
 * `.module` publish acceptance follows the coordinate's existing release/snapshot rules (feature 011,
 * FR-003/SC-003) and the wire authorization applies to `.module` like any file (FR-006): a release
 * `.module` is immutable, a snapshot `.module` is overwritable, and an unauthenticated `.module` PUT to
 * an auth-enabled repo is rejected.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ModuleImmutabilityTest {

    @LocalServerPort
    private var port: Int = 0

    private val http: HttpClient = HttpClient.newHttpClient()
    private val ci = "Basic " + Base64.getEncoder().encodeToString("ci:ci-secret".toByteArray())
    private val module = """{ "formatVersion": "1.1", "variants": [] }""".toByteArray()

    private fun put(path: String, body: ByteArray, auth: String?): Int {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/octet-stream").PUT(HttpRequest.BodyPublishers.ofByteArray(body))
        auth?.let { builder.header("Authorization", it) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    private fun getBytes(path: String): HttpResponse<ByteArray> =
        http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(), HttpResponse.BodyHandlers.ofByteArray())

    @Test
    fun `a release module is immutable`() {
        val path = "/releases/com/acme/widget/1.2.3/widget-1.2.3.module"
        assertEquals(201, put(path, module, ci))
        assertEquals(409, put(path, module, ci))
        val served = getBytes(path)
        assertEquals(200, served.statusCode())
        assertArrayEquals(module, served.body())
    }

    @Test
    fun `a snapshot module is overwritable`() {
        val path = "/snapshots/com/acme/widget/1.0-SNAPSHOT/widget-1.0-SNAPSHOT.module"
        assertEquals(201, put(path, module, ci))
        assertEquals(200, put(path, module, ci))
    }

    @Test
    fun `an unauthenticated module publish is rejected`() {
        assertEquals(401, put("/releases/com/acme/noauth/1.0/noauth-1.0.module", module, null))
    }

    companion object {
        @TempDir
        @JvmStatic
        lateinit var storageRoot: Path

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("relikquary.storage.filesystem.root") { storageRoot.toString() }
            registry.add("relikquary.security.users[0].username") { "ci" }
            registry.add("relikquary.security.users[0].password") { "{noop}ci-secret" }
            registry.add("relikquary.security.users[0].roles[0]") { "PUBLISH" }
        }
    }
}
