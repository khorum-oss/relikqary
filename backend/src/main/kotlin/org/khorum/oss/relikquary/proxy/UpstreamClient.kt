package org.khorum.oss.relikquary.proxy

import io.github.oshai.kotlinlogging.KotlinLogging
import org.khorum.oss.relikquary.config.RepositoryProperties
import org.springframework.stereotype.Component
import java.io.InputStream
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

private val logger = KotlinLogging.logger {}

/** Outcome of fetching a single artifact path from a proxy's upstream (feature 006). */
sealed interface UpstreamResponse {
    /** Upstream returned the artifact (HTTP 200). The caller closes [stream]. */
    data class Found(val stream: InputStream, val contentLength: Long?) : UpstreamResponse

    /** Upstream does not have the artifact (HTTP 404/410). */
    data object NotFound : UpstreamResponse

    /** Upstream was unreachable, timed out, or returned an unexpected/5xx status. */
    data object Error : UpstreamResponse
}

/**
 * Fetches artifacts from a proxy repository's configured upstream over HTTP, honouring the JVM proxy
 * settings (this and many deployments route outbound HTTPS through a proxy) and following redirects
 * (Maven Central commonly 301/302s). Optional Basic credentials are applied only to the upstream
 * request and never exposed to resolving clients (FR-012).
 */
@Component
class UpstreamClient {

    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .proxy(ProxySelector.getDefault())
        .connectTimeout(CONNECT_TIMEOUT)
        .build()

    fun fetch(repo: RepositoryProperties.Repo, artifactPath: String): UpstreamResponse {
        val base = requireNotNull(repo.remoteUrl) { "proxy '${repo.name}' has no remoteUrl" }.trimEnd('/')
        val uri = URI.create("$base/$artifactPath")
        val builder = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).GET()
        repo.remoteUsername?.let { user ->
            val token = Base64.getEncoder().encodeToString("$user:${repo.remotePassword.orEmpty()}".toByteArray())
            builder.header("Authorization", "Basic $token")
        }
        return try {
            val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
            interpret(repo, uri, response)
        } catch (e: java.io.IOException) {
            logger.warn { "Upstream fetch failed for ${repo.name} ($uri): ${e.message}" }
            UpstreamResponse.Error
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn { "Upstream fetch interrupted for ${repo.name} ($uri): ${e.message}" }
            UpstreamResponse.Error
        }
    }

    private fun interpret(
        repo: RepositoryProperties.Repo,
        uri: URI,
        response: HttpResponse<InputStream>,
    ): UpstreamResponse = when (response.statusCode()) {
        in OK_RANGE -> {
            val length = response.headers().firstValueAsLong("content-length").takeIf { it.isPresent }?.asLong
            UpstreamResponse.Found(response.body(), length)
        }
        HTTP_NOT_FOUND, HTTP_GONE -> {
            response.body().close()
            UpstreamResponse.NotFound
        }
        else -> {
            response.body().close()
            logger.warn { "Upstream ${repo.name} ($uri) returned ${response.statusCode()}" }
            UpstreamResponse.Error
        }
    }

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)
        val OK_RANGE = 200..299
        const val HTTP_NOT_FOUND = 404
        const val HTTP_GONE = 410
    }
}
