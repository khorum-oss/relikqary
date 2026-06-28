package org.khorum.oss.relikquary.integration

import com.fasterxml.jackson.databind.ObjectMapper
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

/**
 * Browse/manage API (feature 005). Auth disabled here to focus on browse/delete behaviour; the
 * delete auth matrix is covered by [BrowseDeleteAuthTest].
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["relikquary.security.enabled=false"],
)
class BrowseApiTest {

    @LocalServerPort
    var port: Int = 0

    private val http: HttpClient = HttpClient.newHttpClient()
    private val json = ObjectMapper()

    companion object {
        @TempDir
        @JvmStatic
        lateinit var storageRoot: Path

        @DynamicPropertySource
        @JvmStatic
        fun storageProps(registry: DynamicPropertyRegistry) {
            registry.add("relikquary.storage.filesystem.root") { storageRoot.toString() }
        }
    }

    private fun url(path: String) = URI.create("http://127.0.0.1:$port$path")

    private fun put(path: String, body: ByteArray): Int {
        val req = HttpRequest.newBuilder(url(path)).header("Content-Type", "application/octet-stream")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build()
        return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    private fun get(path: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(url(path)).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun delete(path: String): Int =
        http.send(HttpRequest.newBuilder(url(path)).DELETE().build(), HttpResponse.BodyHandlers.discarding()).statusCode()

    // Each test uses a distinct version because the static storage root is shared and release
    // coordinates are immutable.
    private fun seed(version: String): String {
        val path = "com/example/widget/$version/widget-$version.jar"
        assertEquals(201, put("/releases/$path", byteArrayOf(1, 2, 3, 4, 5)))
        assertEquals(201, put("/releases/$path.sha1", "abc123".toByteArray()))
        return path
    }

    @Test
    fun `lists repositories`() {
        val body = get("/api/repositories").body()
        val names = json.readTree(body).map { it["name"].asText() }
        assertTrue(names.containsAll(listOf("releases", "snapshots"))) { "got $names" }
    }

    @Test
    fun `repositories list reports each kind`() {
        val byName = json.readTree(get("/api/repositories").body()).associateBy { it["name"].asText() }
        assertEquals("HOSTED", byName["releases"]!!["kind"].asText())
        assertEquals("PROXY", byName["maven-central"]!!["kind"].asText())
        assertEquals("GROUP", byName["public"]!!["kind"].asText())
    }

    @Test
    fun `browses contents down to files`() {
        seed("1.0.0")
        val folders = json.readTree(get("/api/repositories/releases/contents/com/example/widget").body())["entries"]
            .map { it["name"].asText() }
        assertTrue(folders.contains("1.0.0")) { "expected version folder, got $folders" }

        val files = json.readTree(get("/api/repositories/releases/contents/com/example/widget/1.0.0").body())["entries"]
        val jar = files.first { it["name"].asText() == "widget-1.0.0.jar" }
        assertEquals("file", jar["kind"].asText())
        assertEquals(5, jar["size"].asInt())
    }

    @Test
    fun `returns file details with checksums and download url`() {
        val base = seed("2.0.0")
        val details = json.readTree(get("/api/repositories/releases/file/$base").body())
        assertEquals(5, details["size"].asInt())
        assertEquals("abc123", details["checksums"]["sha1"].asText())
        assertEquals("/releases/$base", details["downloadUrl"].asText())
    }

    @Test
    fun `deletes a version folder and it is gone`() {
        val base = seed("3.0.0")
        assertEquals(204, delete("/api/repositories/releases/com/example/widget/3.0.0"))
        assertEquals(404, get("/releases/$base").statusCode())
        assertEquals(404, delete("/api/repositories/releases/com/example/widget/3.0.0"))
    }

    @Test
    fun `unknown repository returns 404`() {
        assertEquals(404, get("/api/repositories/nope/contents").statusCode())
    }
}
