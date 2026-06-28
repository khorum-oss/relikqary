package org.khorum.oss.relikquary.observability.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import org.khorum.oss.relikquary.config.StorageProperties
import org.khorum.oss.relikquary.observability.ObservabilityProperties
import org.khorum.oss.relikquary.storage.ArtifactStorage
import org.springframework.stereotype.Component

/**
 * Storage-usage gauges (feature 010, FR-004): total bytes stored and object count, tagged by backend.
 * The total is computed from a full `walk` but served from a value refreshed at most every
 * [ObservabilityProperties.storageUsageRefresh] — a scrape never triggers a store walk, keeping the
 * gauge off the hot path.
 */
@Component
class StorageUsageMetrics(
    private val storage: ArtifactStorage,
    private val storageProperties: StorageProperties,
    private val observability: ObservabilityProperties,
) : MeterBinder {

    @Volatile
    private var cached = Usage(0, 0)

    @Volatile
    private var refreshedAtNanos = 0L

    @Volatile
    private var primed = false

    override fun bindTo(registry: MeterRegistry) {
        val backend = storageProperties.backend.name.lowercase()
        Gauge.builder("relikquary.storage.usage.bytes", this) { it.usage().bytes.toDouble() }
            .tag("backend", backend)
            .register(registry)
        Gauge.builder("relikquary.storage.objects", this) { it.usage().count.toDouble() }
            .tag("backend", backend)
            .register(registry)
    }

    private fun usage(): Usage {
        val now = System.nanoTime()
        if (!primed || now - refreshedAtNanos >= observability.storageUsageRefresh.toNanos()) {
            val all = storage.walk("")
            cached = Usage(all.sumOf { it.sizeBytes }, all.size.toLong())
            refreshedAtNanos = now
            primed = true
        }
        return cached
    }

    private data class Usage(val bytes: Long, val count: Long)
}
