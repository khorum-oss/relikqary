package org.khorum.oss.relikquary.unit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.khorum.oss.relikquary.config.StorageProperties
import org.khorum.oss.relikquary.storage.FilesystemArtifactStorage
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit coverage for the filesystem storage probe backing the readiness indicator (feature 010, FR-002).
 * A reachable/writable root is healthy; a vanished root is unhealthy with a non-secret detail.
 */
class StorageProbeTest {

    private fun storage(root: Path) =
        FilesystemArtifactStorage(StorageProperties(filesystem = StorageProperties.Filesystem(root.toString())))

    @Test
    fun `a writable root probes healthy`(@TempDir tmp: Path) {
        val probe = storage(tmp.resolve("store")).probe()

        assertTrue(probe.healthy)
        assertEquals("filesystem", probe.backend)
        assertNull(probe.detail)
    }

    @Test
    fun `a vanished root probes unhealthy with a non-secret detail`(@TempDir tmp: Path) {
        val root = tmp.resolve("store")
        val storage = storage(root) // constructor creates the root
        Files.delete(root) // simulate the storage backend going away

        val probe = storage.probe()

        assertFalse(probe.healthy)
        assertEquals("filesystem", probe.backend)
        assertNotNull(probe.detail)
    }
}
