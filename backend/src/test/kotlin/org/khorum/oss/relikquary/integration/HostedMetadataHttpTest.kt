package org.khorum.oss.relikquary.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * HTTP-level verification of server-authoritative hosted metadata (feature 014): independent publishers
 * don't clobber the listing, snapshot resolution picks the newest build, checksums match, and concurrent
 * publishes converge. Auth disabled to focus on the metadata behavior.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["relikquary.security.enabled=false"],
)
class HostedMetadataHttpTest {

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

        private const val SLEEP_MS = 1100L
        private const val POOL = 6
        private const val VERSIONS = 8
    }

    @Test
    fun `independent publishers both appear in the served listing`() {
        // Publisher A: widget 1.0.0 + a metadata listing only 1.0.0 (its local view).
        publish("/releases/com/example/widget/1.0.0/widget-1.0.0.jar")
        publish("/releases/com/example/widget/1.0.0/widget-1.0.0.pom")
        put("/releases/com/example/widget/maven-metadata.xml", onlyVersion("widget", "1.0.0"))
        Thread.sleep(SLEEP_MS)
        // Publisher B (never saw 1.0.0): widget 2.0.0 + a metadata listing only 2.0.0.
        publish("/releases/com/example/widget/2.0.0/widget-2.0.0.jar")
        publish("/releases/com/example/widget/2.0.0/widget-2.0.0.pom")
        put("/releases/com/example/widget/maven-metadata.xml", onlyVersion("widget", "2.0.0"))

        val meta = get("/releases/com/example/widget/maven-metadata.xml")
        assertEquals(200, meta.statusCode())
        val body = meta.body().decodeToString()
        assertTrue(body.contains("<version>1.0.0</version>")) { "1.0.0 was clobbered by publisher B" }
        assertTrue(body.contains("<version>2.0.0</version>"))
        assertTrue(body.contains("<release>2.0.0</release>"))
        assertTrue(body.contains("<latest>2.0.0</latest>"))

        // Checksums match the served bytes.
        val sha1 = get("/releases/com/example/widget/maven-metadata.xml.sha1").body().decodeToString().trim()
        assertEquals(hex(meta.body(), "SHA-1"), sha1)
    }

    @Test
    fun `snapshot metadata resolves the newest build`() {
        val dir = "/snapshots/com/example/gadget/4.0.0-SNAPSHOT"
        publish("$dir/gadget-4.0.0-20260630.120000-1.jar")
        publish("$dir/gadget-4.0.0-20260630.120000-1.pom")
        publish("$dir/gadget-4.0.0-20260630.130000-2.jar")
        publish("$dir/gadget-4.0.0-20260630.130000-2.pom")

        val meta = get("$dir/maven-metadata.xml")
        assertEquals(200, meta.statusCode())
        val body = meta.body().decodeToString()
        assertTrue(body.contains("<timestamp>20260630.130000</timestamp>")) { "did not pick the newest build" }
        assertTrue(body.contains("<buildNumber>2</buildNumber>"))
        // Snapshot version is listed at the artifact level too.
        val artifact = get("/snapshots/com/example/gadget/maven-metadata.xml").body().decodeToString()
        assertTrue(artifact.contains("<version>4.0.0-SNAPSHOT</version>"))
    }

    @Test
    fun `concurrent publishes of the same artifact converge to the full listing`() {
        val pool = Executors.newFixedThreadPool(POOL)
        try {
            val tasks = (1..VERSIONS).map { v ->
                pool.submit { publish("/releases/com/acme/thing/$v.0.0/thing-$v.0.0.jar") }
            }
            tasks.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        val body = get("/releases/com/acme/thing/maven-metadata.xml").body().decodeToString()
        for (v in 1..VERSIONS) {
            assertTrue(body.contains("<version>$v.0.0</version>")) { "version $v.0.0 dropped under concurrency" }
        }
    }

    private fun onlyVersion(artifactId: String, version: String): ByteArray =
        ("<metadata><groupId>com.example</groupId><artifactId>$artifactId</artifactId>" +
            "<versioning><versions><version>$version</version></versions></versioning></metadata>")
            .toByteArray()

    private fun publish(path: String) = assertEquals(201, put(path, byteArrayOf(1, 2, 3)))

    private fun put(path: String, body: ByteArray): Int {
        val req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/octet-stream")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build()
        return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    private fun get(path: String): HttpResponse<ByteArray> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

    private fun hex(bytes: ByteArray, algorithm: String): String =
        MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }
}
