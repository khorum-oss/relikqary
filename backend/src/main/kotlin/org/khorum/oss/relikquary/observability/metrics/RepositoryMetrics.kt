package org.khorum.oss.relikquary.observability.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * Domain counters for the repository hot paths (feature 010, FR-004). HTTP rate/latency/error come from
 * Micrometer's auto-configured `http.server.requests`; these add the outcomes that the HTTP status tag
 * can't express — publish accept/reject, resolve hit/miss, and proxy cache + upstream outcomes. Each call
 * is a single counter increment (negligible hot-path cost).
 */
@Component
class RepositoryMetrics(private val registry: MeterRegistry) {

    /** A publish was accepted (`accepted`) or rejected by policy/kind (`rejected`). */
    fun recordPublish(repository: String, outcome: String) =
        counter("relikquary.publish", repository, "outcome", outcome).increment()

    /** A resolve resulted in `hit`, `miss`, or `upstream_error`. */
    fun recordResolve(repository: String, outcome: String) =
        counter("relikquary.resolve", repository, "outcome", outcome).increment()

    /** A proxy cache lookup was a `hit` or a `miss`. */
    fun recordCache(repository: String, result: String) =
        counter("relikquary.proxy.cache", repository, "result", result).increment()

    /** A proxy upstream fetch returned `found`, `not_found`, or `error`. */
    fun recordUpstream(repository: String, outcome: String) =
        counter("relikquary.proxy.upstream", repository, "outcome", outcome).increment()

    private fun counter(name: String, repository: String, tagKey: String, tagValue: String): Counter =
        Counter.builder(name).tag("repository", repository).tag(tagKey, tagValue).register(registry)
}
