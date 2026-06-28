package org.khorum.oss.relikquary.unit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.khorum.oss.relikquary.cleanup.CleanupService
import org.khorum.oss.relikquary.config.RepositoryProperties.CacheEviction
import org.khorum.oss.relikquary.storage.StoredObject
import org.springframework.util.unit.DataSize
import java.time.Duration
import java.time.Instant

class CacheEvictionSelectionTest {

    private val now = Instant.parse("2026-02-01T00:00:00Z")

    private fun cached(name: String, ageDays: Long, size: Long) =
        StoredObject("maven-central/$name", size, now.minus(Duration.ofDays(ageDays)))

    @Test
    fun `maxAge selects cached files older than the cutoff`() {
        val files = listOf(
            cached("a.jar", ageDays = 1, size = 100),
            cached("b.jar", ageDays = 10, size = 100),
            cached("c.jar", ageDays = 20, size = 100),
        )
        val selected = CleanupService.selectCacheEvictions(files, CacheEviction(maxAge = Duration.ofDays(7)), now)
        assertEquals(setOf("maven-central/b.jar", "maven-central/c.jar"), selected.map { it.key }.toSet())
    }

    @Test
    fun `maxSize evicts oldest first until within budget`() {
        val files = listOf(
            cached("new.jar", ageDays = 1, size = 100),
            cached("mid.jar", ageDays = 5, size = 100),
            cached("old.jar", ageDays = 9, size = 100),
        )
        // Total 300; budget 150 ⇒ evict the two oldest (old, mid), leaving new (100 ≤ 150).
        val selected = CleanupService.selectCacheEvictions(files, CacheEviction(maxSize = DataSize.ofBytes(150)), now)
        assertEquals(setOf("maven-central/old.jar", "maven-central/mid.jar"), selected.map { it.key }.toSet())
    }

    @Test
    fun `maven-metadata is never evicted`() {
        val files = listOf(cached("com/x/maven-metadata.xml", ageDays = 99, size = 100))
        assertTrue(CleanupService.selectCacheEvictions(files, CacheEviction(maxAge = Duration.ofDays(1)), now).isEmpty())
    }

    @Test
    fun `age and size combine without double-selecting`() {
        val files = listOf(
            cached("new.jar", ageDays = 1, size = 100),
            cached("old.jar", ageDays = 30, size = 100),
        )
        val selected = CleanupService.selectCacheEvictions(
            files,
            CacheEviction(maxAge = Duration.ofDays(7), maxSize = DataSize.ofBytes(50)),
            now,
        )
        // old evicted by age; new then evicted to meet the 50-byte budget. Each appears once.
        assertEquals(2, selected.size)
        assertEquals(2, selected.map { it.key }.toSet().size)
    }
}
