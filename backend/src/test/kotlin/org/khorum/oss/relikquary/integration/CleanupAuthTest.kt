package org.khorum.oss.relikquary.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.khorum.oss.relikquary.storage.ArtifactStorage
import org.springframework.beans.factory.annotation.Autowired
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
import java.util.Base64

/** US3: the cleanup endpoint requires PUBLISH; a dry-run reports a selection but mutates nothing. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "relikquary.security.enabled=true",
        "relikquary.security.users[0].username=ci",
        "relikquary.security.users[0].password={noop}secret",
        "relikquary.security.users[0].roles[0]=PUBLISH",
        "relikquary.security.users[1].username=reader",
        "relikquary.security.users[1].password={noop}secret",
        "relikquary.security.users[1].roles[0]=READER",
    ],
)
@ActiveProfiles("cleanup")
class CleanupAuthTest {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var storage: ArtifactStorage

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

    private fun basic(u: String, p: String) = "Basic " + Base64.getEncoder().encodeToString("$u:$p".toByteArray())

    private fun cleanup(query: String, auth: String?): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/cleanup$query"))
            .POST(HttpRequest.BodyPublishers.noBody())
        auth?.let { b.header("Authorization", it) }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun put(path: String, auth: String) {
        val req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .header("Authorization", auth).PUT(HttpRequest.BodyPublishers.ofByteArray(byteArrayOf(1))).build()
        assertEquals(201, http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode())
    }

    @Test
    fun `cleanup endpoint requires the PUBLISH authority`() {
        assertEquals(401, cleanup("", auth = null).statusCode())
        assertEquals(403, cleanup("", basic("reader", "secret")).statusCode())
        assertEquals(200, cleanup("", basic("ci", "secret")).statusCode())
    }

    @Test
    fun `dry-run reports a selection but deletes nothing`() {
        val dir = "com/acme/lib/1.0.0-SNAPSHOT"
        for (n in 1..5) put("/snapshots/$dir/lib-1.0.0-2026010$n.120000-$n.jar", basic("ci", "secret"))

        val dry = cleanup("?dryRun=true", basic("ci", "secret"))
        assertEquals(200, dry.statusCode())
        assertTrue(dry.body().contains("\"dryRun\":true"))
        assertTrue(dry.body().contains("\"itemsRemoved\":2")) // builds 1 & 2

        // Storage unchanged by the dry-run.
        assertTrue(storage.exists("snapshots/$dir/lib-1.0.0-20260101.120000-1.jar"))
    }
}
