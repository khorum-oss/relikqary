package org.khorum.oss.relikquary.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.khorum.oss.relikquary.cleanup.CleanupService
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

/** US1: snapshot retention keeps the newest builds and never touches releases/metadata (feature 009). */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["relikquary.security.enabled=false"],
)
@ActiveProfiles("cleanup")
class SnapshotRetentionTest {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var cleanup: CleanupService

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

    private val dir = "com/acme/lib/1.0.0-SNAPSHOT"
    private fun buildKey(n: Int) = "$dir/lib-1.0.0-2026010$n.120000-$n"

    private fun put(path: String) {
        val req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(byteArrayOf(1, 2, 3))).build()
        assertEquals(201, http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode())
    }

    @Test
    fun `keeps the 3 newest snapshot builds, removes older, preserves metadata and releases`() {
        for (n in 1..5) {
            put("/snapshots/${buildKey(n)}.jar")
            put("/snapshots/${buildKey(n)}.pom")
        }
        put("/snapshots/$dir/maven-metadata.xml")
        put("/releases/com/acme/app/1.0.0/app-1.0.0.jar")

        val report = cleanup.run(dryRun = false)
        assertEquals(4, report.itemsRemoved) // builds 1 & 2 → 2 files each

        // Oldest two builds gone; newest three kept.
        assertFalse(storage.exists("snapshots/${buildKey(1)}.jar"))
        assertFalse(storage.exists("snapshots/${buildKey(2)}.pom"))
        for (n in 3..5) assertTrue(storage.exists("snapshots/${buildKey(n)}.jar"))
        // Metadata and the release repo untouched.
        assertTrue(storage.exists("snapshots/$dir/maven-metadata.xml"))
        assertTrue(storage.exists("releases/com/acme/app/1.0.0/app-1.0.0.jar"))

        // A retained build still resolves over the wire.
        val get = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/snapshots/${buildKey(5)}.jar")).GET().build()
        assertEquals(200, http.send(get, HttpResponse.BodyHandlers.discarding()).statusCode())
    }
}
