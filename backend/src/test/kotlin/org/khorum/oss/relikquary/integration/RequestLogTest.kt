package org.khorum.oss.relikquary.integration

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
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
import java.time.Duration
import java.util.Base64

/**
 * Opt-in structured request logging (feature 010, FR-005, SC-003). With the flag on, each request emits
 * exactly one JSON line on the `relikquary.access` logger carrying the documented fields; an
 * authenticated request includes the principal, an anonymous one omits it.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["relikquary.observability.request-log.enabled=true"],
)
@ActiveProfiles("authz")
class RequestLogTest {

    @LocalServerPort
    private var port: Int = 0

    private val http: HttpClient = HttpClient.newHttpClient()
    private val mapper = ObjectMapper()
    private lateinit var accessLogger: Logger
    private lateinit var appender: ListAppender<ILoggingEvent>

    @BeforeEach
    fun attachAppender() {
        accessLogger = LoggerFactory.getLogger("relikquary.access") as Logger
        appender = ListAppender<ILoggingEvent>().apply { start() }
        accessLogger.addAppender(appender)
    }

    @AfterEach
    fun detachAppender() {
        accessLogger.detachAppender(appender)
    }

    private fun basic(user: String, password: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())

    private fun send(builder: HttpRequest.Builder, auth: String?): Int {
        auth?.let { builder.header("Authorization", it) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    private fun awaitOneLine(): ILoggingEvent {
        val deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos()
        while (System.nanoTime() < deadline) {
            if (appender.list.isNotEmpty()) break
            Thread.sleep(SLEEP_MS)
        }
        assertEquals(1, appender.list.size, "exactly one access log line per request")
        return appender.list.first()
    }

    @Test
    fun `a resolve emits one structured line without a principal when anonymous`() {
        send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/releases/com/acme/x/1.0/x-1.0.jar")).GET(), null)

        val line = mapper.readTree(awaitOneLine().formattedMessage)
        assertEquals("GET", line.path("method").asText())
        assertEquals("releases", line.path("repository").asText())
        assertTrue(line.has("path"))
        assertTrue(line.has("status"))
        assertTrue(line.has("bytes"))
        assertTrue(line.has("durationMs"))
        assertFalse(line.has("principal"))
    }

    @Test
    fun `an authenticated publish includes the principal`() {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/releases/com/acme/lib/1.0/lib-1.0.jar"))
            .header("Content-Type", "application/octet-stream")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(byteArrayOf(1, 2, 3)))
        assertEquals(201, send(builder, basic("ci", "pw")))

        val line = mapper.readTree(awaitOneLine().formattedMessage)
        assertEquals("PUT", line.path("method").asText())
        assertEquals("ci", line.path("principal").asText())
    }

    companion object {
        private const val SLEEP_MS = 20L

        @TempDir
        @JvmStatic
        lateinit var storageRoot: Path

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("relikquary.storage.filesystem.root") { storageRoot.toString() }
        }
    }
}

/**
 * With the flag off (the default), no structured request line is emitted — normal logging is unchanged.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["relikquary.security.enabled=false"],
)
class RequestLogDisabledTest {

    @LocalServerPort
    private var port: Int = 0

    private val http: HttpClient = HttpClient.newHttpClient()
    private lateinit var accessLogger: Logger
    private lateinit var appender: ListAppender<ILoggingEvent>

    @BeforeEach
    fun attachAppender() {
        accessLogger = LoggerFactory.getLogger("relikquary.access") as Logger
        appender = ListAppender<ILoggingEvent>().apply { start() }
        accessLogger.addAppender(appender)
    }

    @AfterEach
    fun detachAppender() {
        accessLogger.detachAppender(appender)
    }

    @Test
    fun `no access line is emitted when request logging is disabled`() {
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/releases/com/acme/x/1.0/x-1.0.jar")).GET().build(),
            HttpResponse.BodyHandlers.discarding(),
        )

        assertTrue(appender.list.isEmpty())
    }

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
}
