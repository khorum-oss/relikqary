package org.khorum.oss.relikquary.storage

import org.khorum.oss.relikquary.config.StorageProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Filesystem-backed [ArtifactStorage] rooted at a configurable base directory (FR-002, FR-007).
 *
 * Writes are atomic (temp file + move) so a partially written file is never served, and every
 * resolved path is constrained to stay within the configured root as defence-in-depth (FR-012).
 */
@Component
@ConditionalOnProperty(name = ["relikquary.storage.backend"], havingValue = "filesystem", matchIfMissing = true)
class FilesystemArtifactStorage(props: StorageProperties) : ArtifactStorage {

    private val root: Path = Path.of(props.filesystem.root).toAbsolutePath().normalize()

    init {
        Files.createDirectories(root)
    }

    private fun resolve(key: String): Path {
        val resolved = root.resolve(key).normalize()
        require(resolved.startsWith(root)) { "resolved path escapes storage root: $key" }
        return resolved
    }

    override fun exists(key: String): Boolean = Files.isRegularFile(resolve(key))

    override fun openRead(key: String): StoredArtifact? {
        val path = resolve(key)
        if (!Files.isRegularFile(path)) return null
        return StoredArtifact(Files.newInputStream(path), Files.size(path))
    }

    override fun write(key: String, content: InputStream): Long {
        val target = resolve(key)
        Files.createDirectories(target.parent)
        val tmp = Files.createTempFile(target.parent, ".relikquary-", ".tmp")
        var moved = false
        try {
            val written = content.use { Files.copy(it, tmp, StandardCopyOption.REPLACE_EXISTING) }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            moved = true
            return written
        } finally {
            if (!moved) Files.deleteIfExists(tmp)
        }
    }
}
