package org.khorum.oss.relikquary.unit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.khorum.oss.relikquary.config.StorageProperties
import org.khorum.oss.relikquary.storage.FilesystemArtifactStorage
import java.io.ByteArrayInputStream
import java.nio.file.Path

class FilesystemWalkTest {

    private fun storage(root: Path): FilesystemArtifactStorage =
        FilesystemArtifactStorage(StorageProperties(filesystem = StorageProperties.Filesystem(root = root.toString())))

    @Test
    fun `walk lists every file recursively with size and last-modified`(@TempDir root: Path) {
        val store = storage(root)
        store.write("repo/a/x.jar", ByteArrayInputStream(ByteArray(5)))
        store.write("repo/a/b/y.pom", ByteArrayInputStream(ByteArray(3)))
        store.write("other/z.txt", ByteArrayInputStream(ByteArray(1)))

        val walked = store.walk("repo/").associateBy { it.key }
        assertEquals(setOf("repo/a/x.jar", "repo/a/b/y.pom"), walked.keys)
        assertEquals(5L, walked["repo/a/x.jar"]!!.sizeBytes)
        assertTrue(walked["repo/a/x.jar"]!!.lastModified != null)
    }

    @Test
    fun `walk of an absent prefix returns nothing`(@TempDir root: Path) {
        assertTrue(storage(root).walk("nope/").isEmpty())
    }
}
