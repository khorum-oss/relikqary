package org.khorum.oss.relikquary.observability.logging

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.khorum.oss.relikquary.observability.ObservabilityProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Dedicated access logger so the structured line can be routed/shipped independently of normal logs. */
private val accessLogger = KotlinLogging.logger("relikquary.access")

/**
 * Emits one structured (JSON) log line per request (feature 010, FR-005), opt-in via
 * `relikquary.observability.request-log.enabled` (the bean only exists when enabled, so there is zero
 * overhead when off). The response is wrapped to count body bytes without buffering; the principal is
 * read from the security context (omitted when anonymous). Independent of the normal application log
 * format — it neither replaces nor reformats other logs.
 */
@Component
@ConditionalOnProperty(prefix = "relikquary.observability.request-log", name = ["enabled"], havingValue = "true")
class RequestLoggingFilter(
    private val properties: ObservabilityProperties,
) : OncePerRequestFilter() {

    // A dedicated mapper: the filter is created during web-server startup, before the shared
    // ObjectMapper bean exists, and the event is a simple data class.
    private val objectMapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val wrapped = CountingResponseWrapper(response)
        val startNanos = System.nanoTime()
        try {
            filterChain.doFilter(request, wrapped)
        } finally {
            val event = RequestLogEvent(
                method = request.method,
                repository = repositoryOf(request),
                path = pathOf(request),
                status = wrapped.status,
                bytes = wrapped.bytesWritten,
                durationMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI,
                principal = principal(),
            )
            accessLogger.info { objectMapper.writeValueAsString(event) }
        }
    }

    private fun decodedPath(request: HttpServletRequest): String =
        URLDecoder.decode(request.requestURI.removePrefix(request.contextPath), StandardCharsets.UTF_8).trimStart('/')

    private fun repositoryOf(request: HttpServletRequest): String? = repositoryName(decodedPath(request))

    private fun pathOf(request: HttpServletRequest): String {
        val path = request.requestURI
        val query = request.queryString
        return if (properties.requestLog.includeQueryString && query != null) "$path?$query" else path
    }

    private fun principal(): String? {
        val authentication = SecurityContextHolder.getContext().authentication
        return authentication
            ?.takeIf { it.isAuthenticated && it.name != ANONYMOUS }
            ?.name
    }

    internal companion object {
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val ANONYMOUS = "anonymousUser"
        private val RESERVED_PREFIXES = setOf("actuator", "api", "ui")

        /**
         * The repository name a request targets: the first path segment, unless it is a reserved
         * (non-repository) prefix (`actuator`/`api`/`ui`) — then null. Pure and unit-testable.
         */
        fun repositoryName(decodedPath: String): String? {
            val first = decodedPath.trimStart('/').substringBefore('/')
            return if (first.isEmpty() || first in RESERVED_PREFIXES) null else first
        }
    }
}
