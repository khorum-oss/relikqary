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
import java.util.Base64
import kotlin.random.Random

/**
 * Authentication behaviour with auth enabled (feature 002, contracts/auth.md): publish requires a
 * `PUBLISH` user; read stays open. A publisher and a reader-only user are configured via properties.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "relikqary.security.enabled=true",
        "relikqary.security.users[0].username=publisher",
        "relikqary.security.users[0].password={noop}pub-secret",
        "relikqary.security.users[0].roles[0]=PUBLISH",
        "relikqary.security.users[1].username=reader",
        "relikqary.security.users[1].password={noop}read-secret",
        "relikqary.security.users[1].roles[0]=READER",
    ],
)
class AuthPublishTest {

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

    private fun basic(user: String, password: String) =
        "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())

    private fun put(path: String, body: ByteArray, auth: String? = null): Int {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/octet-stream")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
        auth?.let { builder.header("Authorization", it) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    private fun get(path: String): Int {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build()
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    @Test
    fun `publish without credentials is rejected with 401`() {
        assertEquals(401, put("/com/example/auth/1.0.0/auth-1.0.0.jar", Random.nextBytes(64)))
    }

    @Test
    fun `publish with wrong credentials is rejected with 401`() {
        assertEquals(401, put("/com/example/auth/1.0.1/auth-1.0.1.jar", Random.nextBytes(64), basic("publisher", "wrong")))
    }

    @Test
    fun `authenticated non-publisher is forbidden with 403`() {
        assertEquals(403, put("/com/example/auth/1.0.2/auth-1.0.2.jar", Random.nextBytes(64), basic("reader", "read-secret")))
    }

    @Test
    fun `valid publisher can publish, then anyone can read`() {
        val path = "/com/example/auth/1.0.3/auth-1.0.3.jar"
        assertEquals(201, put(path, Random.nextBytes(256), basic("publisher", "pub-secret")))
        // Read stays open — no credentials required.
        assertEquals(200, get(path))
    }
}
