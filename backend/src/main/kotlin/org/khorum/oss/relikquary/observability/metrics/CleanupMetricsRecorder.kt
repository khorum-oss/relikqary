package org.khorum.oss.relikquary.observability.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.khorum.oss.relikquary.protocol.dto.CleanupReport
import org.springframework.stereotype.Component

/**
 * Records cleanup outcomes (feature 010, FR-004) from the single `CleanupService.run` seam — covering
 * both scheduled and on-demand runs. A dry-run increments only the run counter (nothing is reclaimed);
 * a real run also adds the items removed and bytes reclaimed.
 */
@Component
class CleanupMetricsRecorder(private val registry: MeterRegistry) {

    private val itemsRemoved: Counter = Counter.builder("relikquary.cleanup.items.removed").register(registry)
    private val bytesReclaimed: Counter = Counter.builder("relikquary.cleanup.bytes.reclaimed").register(registry)

    fun record(report: CleanupReport) {
        Counter.builder("relikquary.cleanup.runs").tag("dry_run", report.dryRun.toString()).register(registry).increment()
        if (!report.dryRun) {
            itemsRemoved.increment(report.itemsRemoved.toDouble())
            bytesReclaimed.increment(report.bytesReclaimed.toDouble())
        }
    }
}
