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
import java.util.Base64

/**
 * Managed-user lifecycle over the wire (feature 016, Phase 3, US8): an admin creates a user, that user
 * authenticates with its role, the static-config admin is never locked out, name clashes are rejected,
 * and deletion takes effect. Auth enabled with a single config PUBLISH user 'ci'.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "relikquary.security.enabled=true",
        "relikquary.security.users[0].username=ci",
        "relikquary.security.users[0].password={noop}secret",
        "relikquary.security.users[0].roles[0]=PUBLISH",
    ],
)
class UserApiTest {

    @LocalServerPort
    var port: Int = 0

    private val http: HttpClient = HttpClient.newHttpClient()
    private val json = ObjectMapper()

    companion object {
        @TempDir
        @JvmStatic
        lateinit var storageRoot: Path

        @TempDir
        @JvmStatic
        lateinit var dbDir: Path

        @DynamicPropertySource
        @JvmStatic
        fun storageProps(registry: DynamicPropertyRegistry) {
            registry.add("relikquary.storage.filesystem.root") { storageRoot.toString() }
            // Isolate the app-state DB per run so managed users don't collide with the shared build DB.
            registry.add("relikquary.persistence.sqlite.path") { dbDir.resolve("rq.db").toString() }
        }
    }

    private fun url(path: String) = URI.create("http://127.0.0.1:$port$path")
    private fun basic(user: String, secret: String) =
        "Basic " + Base64.getEncoder().encodeToString("$user:$secret".toByteArray())

    private fun createUser(auth: String?, body: String): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(url("/api/admin/users"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        if (auth != null) builder.header("Authorization", auth)
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun publish(path: String, auth: String?): Int {
        val builder = HttpRequest.newBuilder(url("/releases/$path"))
            .header("Content-Type", "application/octet-stream")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(byteArrayOf(1, 2, 3)))
        if (auth != null) builder.header("Authorization", auth)
        return http.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    private fun statusOf(path: String, auth: String?): Int {
        val builder = HttpRequest.newBuilder(url(path)).GET()
        if (auth != null) builder.header("Authorization", auth)
        return http.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    private val admin = basic("ci", "secret")

    @Test
    fun `admin creates a publisher who can authenticate and publish`() {
        val created = createUser(admin, """{"username":"bob","password":"bobpw","roles":["PUBLISH"]}""")
        assertEquals(201, created.statusCode())

        // Bob authenticates and, with the PUBLISH role, can publish.
        assertEquals(201, publish("com/acme/bob/1.0.0/bob-1.0.0.jar", basic("bob", "bobpw")))

        // Listed without a password hash.
        val list = json.readTree(get200("/api/admin/users"))
        val entry = list.first { it["username"].asText() == "bob" }
        assertTrue(entry["roles"].map { it.asText() }.contains("PUBLISH"))
        assertTrue(entry.findValuesAsText("passwordHash").isEmpty()) { "the hash must never be listed" }
    }

    @Test
    fun `a viewer with no roles cannot publish - config admin is never locked out`() {
        assertEquals(201, createUser(admin, """{"username":"vic","password":"vicpw","roles":[]}""").statusCode())

        // Viewer reads (open) but cannot publish, and cannot use the admin API.
        assertEquals(403, publish("com/acme/vic/1.0.0/vic-1.0.0.jar", basic("vic", "vicpw")))
        assertEquals(403, statusOf("/api/admin/users", basic("vic", "vicpw")))

        // The static-config admin still authenticates and manages users.
        assertEquals(200, statusOf("/api/admin/users", admin))
    }

    @Test
    fun `duplicate and config-colliding usernames are rejected`() {
        assertEquals(201, createUser(admin, """{"username":"dup","password":"p","roles":[]}""").statusCode())
        assertEquals(409, createUser(admin, """{"username":"dup","password":"p","roles":[]}""").statusCode())
        // 'ci' is a config user — a managed account cannot shadow it.
        assertEquals(409, createUser(admin, """{"username":"ci","password":"p","roles":[]}""").statusCode())
    }

    @Test
    fun `a deleted user can no longer authenticate`() {
        val id = json.readTree(
            createUser(admin, """{"username":"tmp","password":"tmppw","roles":["PUBLISH"]}""").body(),
        )["id"].asText()
        assertEquals(200, statusOf("/api/admin/users", basic("tmp", "tmppw"))) // tmp is a PUBLISH admin

        val delete = HttpRequest.newBuilder(url("/api/admin/users/$id")).header("Authorization", admin).DELETE().build()
        assertEquals(204, http.send(delete, HttpResponse.BodyHandlers.discarding()).statusCode())

        assertEquals(401, statusOf("/api/admin/users", basic("tmp", "tmppw")))
    }

    @Test
    fun `anonymous cannot manage users`() {
        assertEquals(401, statusOf("/api/admin/users", null))
        assertEquals(401, createUser(null, """{"username":"x","password":"p","roles":[]}""").statusCode())
    }

    private fun get200(path: String): String {
        val res = http.send(
            HttpRequest.newBuilder(url(path)).header("Authorization", admin).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, res.statusCode())
        return res.body()
    }
}
