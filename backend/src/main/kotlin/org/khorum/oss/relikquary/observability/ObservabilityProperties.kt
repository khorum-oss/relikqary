package org.khorum.oss.relikquary.observability

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Observability knobs (feature 010), all optional with safe defaults (FR-007). The standard Spring
 * `management.*` keys (endpoint exposure, health groups, status mapping) live in `application.yml`;
 * this holds Relikquary-specific tuning: the opt-in structured request log and the cache TTLs that keep
 * health probes and the storage-usage gauge off the hot path.
 */
@ConfigurationProperties(prefix = "relikquary.observability")
data class ObservabilityProperties(
    val requestLog: RequestLog = RequestLog(),
    /** TTL cache for the storage readiness probe — collapses a burst of readiness scrapes to one check. */
    val storageProbeTtl: Duration = DEFAULT_STORAGE_PROBE_TTL,
    /** TTL cache for proxy-upstream reachability checks — health scrapes never hammer upstreams. */
    val upstreamHealthTtl: Duration = DEFAULT_UPSTREAM_HEALTH_TTL,
    /** Refresh interval for the cached storage-usage gauges — a full storage walk is never per-scrape. */
    val storageUsageRefresh: Duration = DEFAULT_STORAGE_USAGE_REFRESH,
) {
    /** The per-request structured (JSON) log line — off by default (FR-005). */
    data class RequestLog(
        val enabled: Boolean = false,
        val includeQueryString: Boolean = false,
    )

    companion object {
        private val DEFAULT_STORAGE_PROBE_TTL: Duration = Duration.ofSeconds(2)
        private val DEFAULT_UPSTREAM_HEALTH_TTL: Duration = Duration.ofSeconds(30)
        private val DEFAULT_STORAGE_USAGE_REFRESH: Duration = Duration.ofMinutes(5)
    }
}
