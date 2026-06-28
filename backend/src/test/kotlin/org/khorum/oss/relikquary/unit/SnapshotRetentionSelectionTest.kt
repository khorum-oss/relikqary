package org.khorum.oss.relikquary.unit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.khorum.oss.relikquary.cleanup.CleanupService
import org.khorum.oss.relikquary.config.RepositoryProperties.SnapshotRetention
import org.khorum.oss.relikquary.storage.StoredObject
import java.time.Instant
import java.time.temporal.ChronoUnit

class SnapshotRetentionSelectionTest {

    private val now = Instant.parse("2026-02-01T00:00:00Z")
    private val dir = "snapshots/com/acme/lib/1.0.0-SNAPSHOT"

    // Build n is the n-th day of January 2026 (so build 5 is newest).
    private fun build(n: Int): List<StoredObject> {
        val ts = "2026010$n.120000-$n"
        val day = Instant.parse("2026-01-0${n}T12:00:00Z")
        return listOf(
            StoredObject("$dir/lib-1.0.0-$ts.jar", 100, day),
            StoredObject("$dir/lib-1.0.0-$ts.pom", 10, day),
        )
    }

    private val metadata = StoredObject("$dir/maven-metadata.xml", 50, now)
    private val allFiles = (1..5).flatMap { build(it) } + metadata

    private fun keys(objs: List<StoredObject>) = objs.map { it.key }.toSet()

    @Test
    fun `keepLast trims the oldest builds, keeps the newest, never touches metadata`() {
        val selected = CleanupService.selectSnapshotDeletions(allFiles, SnapshotRetention(keepLast = 3), now)
        // builds 1 and 2 (oldest) removed; 3,4,5 and metadata kept.
        assertEquals(keys(build(1) + build(2)), keys(selected))
        assertTrue(selected.none { it.key.endsWith("maven-metadata.xml") })
    }

    @Test
    fun `maxAge purges builds older than the cutoff but always keeps the newest`() {
        // 20 days before now (2026-02-01) ⇒ cutoff 2026-01-12; builds 1..5 are all older, but the newest
        // (build 5) is always protected.
        val selected = CleanupService.selectSnapshotDeletions(
            allFiles,
            SnapshotRetention(maxAge = java.time.Duration.ofDays(20)),
            now,
        )
        assertEquals(keys(build(1) + build(2) + build(3) + build(4)), keys(selected))
    }

    @Test
    fun `keepLast of zero still protects the newest build`() {
        val selected = CleanupService.selectSnapshotDeletions(allFiles, SnapshotRetention(keepLast = 0), now)
        assertEquals(keys((1..4).flatMap { build(it) }), keys(selected))
    }

    @Test
    fun `an empty policy selects nothing`() {
        assertTrue(CleanupService.selectSnapshotDeletions(allFiles, SnapshotRetention(), now).isEmpty())
    }

    @Test
    fun `non-snapshot and non-timestamped files are never selected`() {
        val release = StoredObject("releases/com/acme/lib/1.0.0/lib-1.0.0.jar", 100, now.minus(99, ChronoUnit.DAYS))
        val plain = StoredObject("$dir/lib-1.0.0-SNAPSHOT.jar", 100, now.minus(99, ChronoUnit.DAYS))
        val selected = CleanupService.selectSnapshotDeletions(
            listOf(release, plain, metadata),
            SnapshotRetention(keepLast = 1, maxAge = java.time.Duration.ofDays(1)),
            now,
        )
        assertTrue(selected.isEmpty())
    }
}
