package org.khorum.oss.relikquary.metadata

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.khorum.oss.relikquary.config.RepositoryProperties
import org.khorum.oss.relikquary.config.StorageProperties
import org.khorum.oss.relikquary.coordinate.RepositoryPath
import org.khorum.oss.relikquary.repository.RepositoryKind
import org.khorum.oss.relikquary.repository.RepositoryType
import org.khorum.oss.relikquary.storage.FilesystemArtifactStorage
import java.nio.file.Path

/** Unit tests for [HostedMetadataService] enumeration against real filesystem storage (feature 014). */
class HostedMetadataServiceTest {

    private fun storageAt(root: Path) =
        FilesystemArtifactStorage(StorageProperties(filesystem = StorageProperties.Filesystem(root = root.toString())))

    private val releases = RepositoryProperties.Repo(name = "releases", kind = RepositoryKind.HOSTED, type = RepositoryType.RELEASE)

    private fun seed(storage: FilesystemArtifactStorage, key: String) =
        storage.write(key, "bytes".byteInputStream())

    @Test
    fun `regenerate lists every stored version regardless of upload order`(@TempDir root: Path) {
        val storage = storageAt(root)
        val service = HostedMetadataService(storage)

        seed(storage, "releases/com/example/widget/1.0.0/widget-1.0.0.jar")
        service.regenerate(releases, RepositoryPath.of("com/example/widget/1.0.0/widget-1.0.0.jar"))
        Thread.sleep(SLEEP_MS) // distinct mtimes so "latest" is deterministic
        seed(storage, "releases/com/example/widget/2.0.0/widget-2.0.0.jar")
        service.regenerate(releases, RepositoryPath.of("com/example/widget/2.0.0/widget-2.0.0.jar"))

        val meta = storage.openRead("releases/com/example/widget/maven-metadata.xml")!!
            .stream.readBytes().decodeToString()
        assertTrue(meta.contains("<version>1.0.0</version>")) { "1.0.0 dropped from listing" }
        assertTrue(meta.contains("<version>2.0.0</version>")) { "2.0.0 dropped from listing" }
        assertTrue(meta.contains("<latest>2.0.0</latest>"))
        assertTrue(meta.contains("<release>2.0.0</release>"))
        // Checksums are written and consistent with the served bytes.
        assertTrue(storage.exists("releases/com/example/widget/maven-metadata.xml.sha1"))
        assertTrue(storage.exists("releases/com/example/widget/maven-metadata.xml.md5"))
    }

    @Test
    fun `regenerate builds version-level snapshot metadata for the newest build`(@TempDir root: Path) {
        val storage = storageAt(root)
        val service = HostedMetadataService(storage)
        val dir = "snapshots/com/example/widget/3.0.0-SNAPSHOT"

        seed(storage, "$dir/widget-3.0.0-20260630.120000-1.jar")
        seed(storage, "$dir/widget-3.0.0-20260630.120000-1.pom")
        seed(storage, "$dir/widget-3.0.0-20260630.130000-2.jar")
        seed(storage, "$dir/widget-3.0.0-20260630.130000-2.pom")
        service.regenerate(
            RepositoryProperties.Repo(name = "snapshots", kind = RepositoryKind.HOSTED, type = RepositoryType.SNAPSHOT),
            RepositoryPath.of("com/example/widget/3.0.0-SNAPSHOT/widget-3.0.0-20260630.130000-2.jar"),
        )

        val meta = storage.openRead("$dir/maven-metadata.xml")!!.stream.readBytes().decodeToString()
        assertTrue(meta.contains("<timestamp>20260630.130000</timestamp>")) { "did not pick newest build" }
        assertEquals(true, meta.contains("<buildNumber>2</buildNumber>"))
        assertTrue(meta.contains("<value>3.0.0-20260630.130000-2</value>"))
        // The snapshot version is also listed at the artifact level.
        val artifact = storage.openRead("snapshots/com/example/widget/maven-metadata.xml")!!
            .stream.readBytes().decodeToString()
        assertTrue(artifact.contains("<version>3.0.0-SNAPSHOT</version>"))
    }

    @Test
    fun `mixed artifact lists releases and snapshots with release pointing at the highest release`(@TempDir root: Path) {
        val storage = storageAt(root)
        val service = HostedMetadataService(storage)
        val mixed = RepositoryProperties.Repo(name = "mixed", kind = RepositoryKind.HOSTED, type = RepositoryType.MIXED)

        seed(storage, "mixed/com/example/widget/1.0.0/widget-1.0.0.jar")
        service.regenerate(mixed, RepositoryPath.of("com/example/widget/1.0.0/widget-1.0.0.jar"))
        Thread.sleep(SLEEP_MS)
        seed(storage, "mixed/com/example/widget/2.0.0-SNAPSHOT/widget-2.0.0-20260630.120000-1.jar")
        service.regenerate(mixed, RepositoryPath.of("com/example/widget/2.0.0-SNAPSHOT/widget-2.0.0-20260630.120000-1.jar"))

        val meta = storage.openRead("mixed/com/example/widget/maven-metadata.xml")!!
            .stream.readBytes().decodeToString()
        assertTrue(meta.contains("<version>1.0.0</version>"))
        assertTrue(meta.contains("<version>2.0.0-SNAPSHOT</version>"))
        assertTrue(meta.contains("<latest>2.0.0-SNAPSHOT</latest>")) { "latest is the newest overall" }
        assertTrue(meta.contains("<release>1.0.0</release>")) { "release is the highest release version" }
    }

    private companion object {
        const val SLEEP_MS = 1100L
    }
}
