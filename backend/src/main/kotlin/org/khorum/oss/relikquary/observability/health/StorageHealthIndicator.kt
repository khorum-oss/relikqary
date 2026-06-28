package org.khorum.oss.relikquary.observability.health

import org.khorum.oss.relikquary.observability.ObservabilityProperties
import org.khorum.oss.relikquary.storage.ArtifactStorage
import org.khorum.oss.relikquary.storage.StorageProbe
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

/**
 * Readiness health for the active storage backend (feature 010, FR-002/FR-008). Reports `UP` when the
 * backend is reachable/writable and `DOWN` otherwise, so the `readiness` group flips to not-ready (503)
 * when storage is unavailable and recovers when it returns. The bean id `storage` is the member name in
 * the readiness group (see `application.yml`). The probe is TTL-cached so a burst of readiness scrapes
 * collapses to one backend call; detail carries only the backend label and a non-secret reason (FR-009).
 */
@Component("storage")
class StorageHealthIndicator(
    private val storage: ArtifactStorage,
    private val properties: ObservabilityProperties,
) : HealthIndicator {

    private val cached = AtomicReference<Snapshot?>(null)

    override fun health(): Health {
        val probe = probe()
        val builder = if (probe.healthy) Health.up() else Health.down()
        builder.withDetail("backend", probe.backend)
        probe.detail?.let { builder.withDetail("detail", it) }
        return builder.build()
    }

    private fun probe(): StorageProbe {
        val now = System.nanoTime()
        cached.get()?.let { if (now - it.atNanos < properties.storageProbeTtl.toNanos()) return it.probe }
        val fresh = storage.probe()
        cached.set(Snapshot(now, fresh))
        return fresh
    }

    private data class Snapshot(val atNanos: Long, val probe: StorageProbe)
}
