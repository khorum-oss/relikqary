package org.khorum.oss.relikquary.observability.metrics

import org.khorum.oss.relikquary.observability.ObservabilityProperties
import org.khorum.oss.relikquary.storage.ArtifactStorage
import org.springframework.stereotype.Component

/**
 * The single source of the storage-usage snapshot (total bytes + object count) used by both the
 * Micrometer gauges (feature 010) and the `/api/stats` Dashboard feed (feature 016, Phase 2). The total
 * is computed from a full `walk` but served from a value refreshed at most every
 * [ObservabilityProperties.storageUsageRefresh], so neither a metrics scrape nor a stats request ever
 * triggers a store walk on the hot path.
 */
@Component
class StorageUsage(
    private val storage: ArtifactStorage,
    private val observability: ObservabilityProperties,
) {

    @Volatile
    private var cached = Usage(0, 0)

    @Volatile
    private var refreshedAtNanos = 0L

    @Volatile
    private var primed = false

    /** The current (possibly cached) usage snapshot; recomputes only past the refresh interval. */
    @Synchronized
    fun snapshot(): Usage {
        val now = System.nanoTime()
        if (!primed || now - refreshedAtNanos >= observability.storageUsageRefresh.toNanos()) {
            val all = storage.walk("")
            cached = Usage(all.sumOf { it.sizeBytes }, all.size.toLong())
            refreshedAtNanos = now
            primed = true
        }
        return cached
    }

    data class Usage(val bytes: Long, val count: Long)
}
