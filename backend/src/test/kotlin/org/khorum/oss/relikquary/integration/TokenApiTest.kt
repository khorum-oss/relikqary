package org.khorum.oss.relikquary.integration

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
 * API token lifecycle over the wire (feature 016, Phase 3, US7): an admin mints a scoped token, a client
 * authenticates with it exactly like a password (Basic), scope governs publish vs read, and revocation
 * takes effect. Auth enabled with a single PUBLISH user 'ci'.
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
class TokenApiTest {

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
            // Isolate the app-state DB per run so tokens don't accumulate in the shared build DB.
            registry.add("relikquary.persistence.sqlite.path") { dbDir.resolve("rq.db").toString() }
        }
    }

    private fun url(path: String) = URI.create("http://127.0.0.1:$port$path")
    private fun basic(user: String, secret: String) =
        "Basic " + Base64.getEncoder().encodeToString("$user:$secret".toByteArray())

    private fun createToken(scope: String, name: String): HttpResponse<String> {
        val req = HttpRequest.newBuilder(url("/api/admin/tokens"))
            .header("Authorization", basic("ci", "secret"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"name":"$name","scope":"$scope"}"""))
            .build()
        return http.send(req, HttpResponse.BodyHandlers.ofString())
    }

    private fun secretOf(response: HttpResponse<String>): String = json.readTree(response.body())["secret"].asText()

    private fun publish(path: String, auth: String?): Int {
        val builder = HttpRequest.newBuilder(url("/releases/$path"))
            .header("Content-Type", "application/octet-stream")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(byteArrayOf(1, 2, 3)))
        if (auth != null) builder.header("Authorization", auth)
        return http.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    private fun get(path: String, auth: String?): Int {
        val builder = HttpRequest.newBuilder(url(path)).GET()
        if (auth != null) builder.header("Authorization", auth)
        return http.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    @Test
    fun `a publish-scoped token publishes and resolves - secret shown once`() {
        val created = createToken("publish", "ci-deploy")
        assertEquals(201, created.statusCode())
        val secret = secretOf(created)
        assertTrue(secret.startsWith("rlq_")) { "secret should be an rlq_ token: $secret" }

        val auth = basic("token", secret)
        assertEquals(201, publish("com/acme/pub/1.0.0/pub-1.0.0.jar", auth))
        assertEquals(200, get("/releases/com/acme/pub/1.0.0/pub-1.0.0.jar", auth))

        // Listing never re-reveals the secret and shows the owner.
        val list = json.readTree(get200Body("/api/admin/tokens"))
        val entry = list.first { it["name"].asText() == "ci-deploy" }
        assertEquals("ci", entry["owner"].asText())
        assertEquals("publish", entry["scope"].asText())
        assertFalse(entry.has("secret")) { "the secret must never be listed" }
    }

    @Test
    fun `a read-scoped token cannot publish`() {
        val secret = secretOf(createToken("read", "readonly"))
        val status = publish("com/acme/readonly/1.0.0/readonly-1.0.0.jar", basic("token", secret))
        assertTrue(status == 401 || status == 403) { "a read token must not publish, got $status" }
    }

    @Test
    fun `a revoked token is rejected`() {
        val created = createToken("publish", "throwaway")
        val secret = secretOf(created)
        val id = json.readTree(created.body())["id"].asText()
        assertEquals(201, publish("com/acme/rev/1.0.0/rev-1.0.0.jar", basic("token", secret)))

        val revoke = HttpRequest.newBuilder(url("/api/admin/tokens/$id"))
            .header("Authorization", basic("ci", "secret")).DELETE().build()
        assertEquals(204, http.send(revoke, HttpResponse.BodyHandlers.discarding()).statusCode())

        // The revoked token no longer authenticates.
        assertEquals(401, get("/api/admin/tokens", basic("token", secret)))
    }

    @Test
    fun `an unknown token and anonymous admin access are rejected`() {
        assertEquals(401, get("/api/admin/tokens", basic("token", "rlq_totally-made-up")))
        assertEquals(401, http.send(
            HttpRequest.newBuilder(url("/api/admin/tokens")).GET().build(),
            HttpResponse.BodyHandlers.discarding(),
        ).statusCode())
    }

    private fun get200Body(path: String): String {
        val res = http.send(
            HttpRequest.newBuilder(url(path)).header("Authorization", basic("ci", "secret")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, res.statusCode())
        return res.body()
    }
}
