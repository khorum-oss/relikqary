package org.khorum.oss.relikquary.observability.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import org.khorum.oss.relikquary.config.StorageProperties
import org.springframework.stereotype.Component

/**
 * Storage-usage gauges (feature 010, FR-004): total bytes stored and object count, tagged by backend.
 * Values come from the shared [StorageUsage] snapshot, so a scrape never triggers a store walk.
 */
@Component
class StorageUsageMetrics(
    private val usage: StorageUsage,
    private val storageProperties: StorageProperties,
) : MeterBinder {

    override fun bindTo(registry: MeterRegistry) {
        val backend = storageProperties.backend.name.lowercase()
        Gauge.builder("relikquary.storage.usage.bytes", usage) { it.snapshot().bytes.toDouble() }
            .tag("backend", backend)
            .register(registry)
        Gauge.builder("relikquary.storage.objects", usage) { it.snapshot().count.toDouble() }
            .tag("backend", backend)
            .register(registry)
    }
}
