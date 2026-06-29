package org.khorum.oss.relikquary.unit

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.khorum.oss.relikquary.observability.logging.CountingResponseWrapper
import org.khorum.oss.relikquary.observability.logging.RequestLogEvent
import org.khorum.oss.relikquary.observability.logging.RequestLoggingFilter
import org.springframework.mock.web.MockHttpServletResponse

/**
 * Unit coverage for the structured request log (feature 010, FR-005): repository derivation, the JSON
 * line shape (single line, exact keys, principal/repository omitted when null), and the response-byte
 * count including the Content-Length fallback when nothing is streamed.
 */
class RequestLogEventTest {

    private val mapper = ObjectMapper().registerKotlinModule()

    @Test
    fun `repository name is the first path segment`() {
        assertEquals("releases", RequestLoggingFilter.repositoryName("/releases/com/acme/lib/1.0/lib-1.0.jar"))
        assertEquals("maven-central", RequestLoggingFilter.repositoryName("maven-central/com/acme/lib"))
    }

    @Test
    fun `reserved prefixes and empty paths are not repositories`() {
        assertNull(RequestLoggingFilter.repositoryName("/actuator/health/readiness"))
        assertNull(RequestLoggingFilter.repositoryName("/api/repositories"))
        assertNull(RequestLoggingFilter.repositoryName("/ui/index.html"))
        assertNull(RequestLoggingFilter.repositoryName("/"))
        assertNull(RequestLoggingFilter.repositoryName(""))
    }

    @Test
    fun `serialized event is a single line with exactly the documented keys`() {
        val json = mapper.writeValueAsString(
            RequestLogEvent("GET", "releases", "/releases/x.jar", 200, 42, 7, "ci"),
        )

        assertFalse(json.contains("\n"))
        @Suppress("UNCHECKED_CAST")
        val keys = (mapper.readValue(json, Map::class.java) as Map<String, Any>).keys
        assertEquals(setOf("method", "repository", "path", "status", "bytes", "durationMs", "principal"), keys)
    }

    @Test
    fun `principal and repository are omitted when null`() {
        val json = mapper.writeValueAsString(
            RequestLogEvent("GET", null, "/actuator/health", 200, 0, 1, null),
        )

        assertFalse(json.contains("principal"))
        assertFalse(json.contains("repository"))
    }

    @Test
    fun `counts streamed bytes`() {
        val wrapper = CountingResponseWrapper(MockHttpServletResponse())
        wrapper.outputStream.write(ByteArray(10))

        assertEquals(10L, wrapper.bytesWritten)
    }

    @Test
    fun `falls back to Content-Length when nothing is streamed`() {
        val response = MockHttpServletResponse()
        response.setContentLength(123)
        val wrapper = CountingResponseWrapper(response)

        assertEquals(123L, wrapper.bytesWritten)
    }
}
