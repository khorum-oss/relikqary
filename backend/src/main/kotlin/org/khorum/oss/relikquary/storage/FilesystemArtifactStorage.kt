package org.khorum.oss.relikquary.storage

import org.khorum.oss.relikquary.config.StorageProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import kotlin.io.path.name

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

    override fun list(prefix: String): List<StorageEntry> {
        val dir = resolve(prefix)
        if (!Files.isDirectory(dir)) return emptyList()
        Files.list(dir).use { stream ->
            return stream
                .filter { !it.name.startsWith(".relikquary-") }
                .map { path ->
                    if (Files.isDirectory(path)) {
                        StorageEntry(name = path.name, isDirectory = true)
                    } else {
                        StorageEntry(path.name, false, Files.size(path), Files.getLastModifiedTime(path).toInstant())
                    }
                }
                .sorted(compareBy({ !it.isDirectory }, { it.name }))
                .toList()
        }
    }

    override fun walk(prefix: String): List<StoredObject> {
        val base = resolve(prefix)
        if (!Files.isDirectory(base)) return emptyList()
        Files.walk(base).use { stream ->
            return stream
                .filter { Files.isRegularFile(it) && !it.name.startsWith(".relikquary-") }
                .map { path ->
                    val key = root.relativize(path).toString().replace('\\', '/')
                    StoredObject(key, Files.size(path), Files.getLastModifiedTime(path).toInstant())
                }
                .toList()
        }
    }

    override fun delete(key: String): Boolean {
        val path = resolve(key)
        if (!Files.isRegularFile(path)) return false
        Files.delete(path)
        pruneEmptyParents(path.parent)
        return true
    }

    override fun deletePrefix(prefix: String): Int {
        val dir = resolve(prefix)
        if (!Files.isDirectory(dir)) return 0
        var count = 0
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { path ->
                if (Files.isRegularFile(path)) count++
                Files.delete(path)
            }
        }
        pruneEmptyParents(dir.parent)
        return count
    }

    override fun probe(): StorageProbe =
        if (Files.isDirectory(root) && Files.isWritable(root)) {
            StorageProbe(healthy = true, backend = "filesystem")
        } else {
            StorageProbe(healthy = false, backend = "filesystem", detail = "storage root is not a writable directory")
        }

    /** Removes now-empty directories up toward (but not including) the storage root. */
    private fun pruneEmptyParents(start: Path?) {
        var current = start
        while (current != null && current != root && current.startsWith(root)) {
            val isEmpty = Files.isDirectory(current) && Files.list(current).use { !it.findFirst().isPresent }
            if (!isEmpty) break
            Files.delete(current)
            current = current.parent
        }
    }
}
