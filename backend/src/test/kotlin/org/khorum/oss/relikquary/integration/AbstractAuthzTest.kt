package org.khorum.oss.relikquary.integration

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * Base for the feature 007 authorization integration tests: boots the app with the `authz` profile
 * (see `application-authz.yml`: users alice/bob/ci and repos releases/privlib/openmirror/grp). Each
 * concrete subclass supplies its own `@TempDir` storage root via `@DynamicPropertySource`.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("authz")
abstract class AbstractAuthzTest {

    @LocalServerPort
    protected var port: Int = 0

    protected val http: HttpClient = HttpClient.newHttpClient()

    protected fun basic(user: String, password: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())

    protected fun get(path: String, auth: String? = null): HttpResponse<ByteArray> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET()
        auth?.let { builder.header("Authorization", it) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    protected fun put(path: String, body: ByteArray, auth: String? = null): Int {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/octet-stream")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
        auth?.let { builder.header("Authorization", it) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    protected fun delete(path: String, auth: String? = null): Int {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).DELETE()
        auth?.let { builder.header("Authorization", it) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
    }
}
