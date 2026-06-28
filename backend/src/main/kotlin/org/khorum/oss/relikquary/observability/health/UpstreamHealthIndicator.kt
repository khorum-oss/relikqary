package org.khorum.oss.relikquary.observability.health

import org.khorum.oss.relikquary.config.RepositoryProperties
import org.khorum.oss.relikquary.observability.ObservabilityProperties
import org.khorum.oss.relikquary.repository.RepositoryKind
import org.khorum.oss.relikquary.repository.RepositoryRegistry
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.health.contributor.Status
import org.springframework.stereotype.Component
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * Detail-only health for proxy upstreams (feature 010, FR-003). Reports each PROXY repository's
 * reachability; if one or more are unreachable the component is [DEGRADED] (a custom status mapped to
 * HTTP 200). Deliberately NOT a member of the `liveness`/`readiness` groups, so a transient upstream
 * outage degrades the detailed health view without ever taking the instance out of service. The check is
 * TTL-cached so health scrapes don't hammer upstreams, and detail carries only `{repo: {reachable}}` —
 * never upstream credentials (FR-009).
 */
@Component("upstreams")
class UpstreamHealthIndicator(
    private val registry: RepositoryRegistry,
    private val properties: ObservabilityProperties,
) : HealthIndicator {

    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .proxy(ProxySelector.getDefault())
        .connectTimeout(CONNECT_TIMEOUT)
        .build()

    private val cached = AtomicReference<Snapshot?>(null)

    override fun health(): Health {
        val now = System.nanoTime()
        cached.get()?.let { if (now - it.atNanos < properties.upstreamHealthTtl.toNanos()) return it.health }
        val fresh = compute()
        cached.set(Snapshot(now, fresh))
        return fresh
    }

    private fun compute(): Health {
        val proxies = registry.all().filter { it.kind == RepositoryKind.PROXY }
        if (proxies.isEmpty()) return Health.up().build()
        val reachable = proxies.associate { it.name to reachable(it) }
        val builder = Health.status(if (reachable.values.all { it }) Status.UP else DEGRADED)
        reachable.forEach { (name, ok) -> builder.withDetail(name, mapOf("reachable" to ok)) }
        return builder.build()
    }

    // Any HTTP response (even 404) proves the upstream is reachable; only a connection failure/timeout
    // counts as unreachable. The exception is swallowed by design — it is the "unreachable" signal.
    @Suppress("SwallowedException")
    private fun reachable(repo: RepositoryProperties.Repo): Boolean {
        val base = repo.remoteUrl?.trimEnd('/') ?: return false
        return try {
            val request = HttpRequest.newBuilder(URI.create(base))
                .timeout(REQUEST_TIMEOUT)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build()
            http.send(request, HttpResponse.BodyHandlers.discarding())
            true
        } catch (e: java.io.IOException) {
            false
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private data class Snapshot(val atNanos: Long, val health: Health)

    private companion object {
        val DEGRADED = Status("DEGRADED")
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
