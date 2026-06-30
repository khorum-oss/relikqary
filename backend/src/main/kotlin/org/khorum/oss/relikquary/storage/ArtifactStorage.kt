package org.khorum.oss.relikquary.storage

import java.io.InputStream
import java.time.Instant

/**
 * Stores and serves artifact files keyed by their repository-layout path. Implementations MUST
 * preserve bytes exactly (FR-003) and never alter, re-encode, or re-checksum stored content. Browse
 * and read operations never mutate; only [write], [delete], and [deletePrefix] change state.
 */
interface ArtifactStorage {

    /** Whether a regular file is stored at [key]. */
    fun exists(key: String): Boolean

    /** Opens the stored bytes at [key], or returns null if absent. The caller closes the stream. */
    fun openRead(key: String): StoredArtifact?

    /** Persists [content] at [key], replacing any existing content atomically. Returns bytes written. */
    fun write(key: String, content: InputStream): Long

    /**
     * Opens a pending write at [key] that becomes visible only on [ArtifactWrite.commit] (feature 015).
     * Used to stream bytes into the cache while serving them, so an aborted/failed transfer never leaves
     * a partial entry. The caller commits on a fully successful transfer and aborts otherwise.
     */
    fun openWrite(key: String): ArtifactWrite

    /** Lists the immediate children (folders and files) directly under [prefix] (empty = root). */
    fun list(prefix: String): List<StorageEntry>

    /** Deletes the single file at [key]. Returns true if a file was removed, false if it was absent. */
    fun delete(key: String): Boolean

    /** Recursively deletes every file under [prefix]. Returns the number of files removed. */
    fun deletePrefix(prefix: String): Int

    /**
     * Recursively lists every file under [prefix] (empty = whole store) with its full key, size, and
     * last-modified time. Used by retention/cleanup (feature 009); never returns directories.
     */
    fun walk(prefix: String): List<StoredObject>

    /**
     * Cheap, side-effect-free reachability/writability check backing the readiness probe (feature 010).
     * Never writes, reads, or re-checksums stored artifacts, and never returns secrets.
     */
    fun probe(): StorageProbe
}

/** Result of a storage reachability/writability check (feature 010 readiness probe). [detail] is never a secret. */
data class StorageProbe(
    val healthy: Boolean,
    val backend: String,
    val detail: String? = null,
)

/** A stored file's full key, size, and last-modified time (feature 009 cleanup enumeration). */
data class StoredObject(
    val key: String,
    val sizeBytes: Long,
    val lastModified: Instant? = null,
)

/**
 * A readable handle to a stored artifact and its size in bytes. [sizeBytes] is null when the size is
 * not known up front — only for a streamed proxy-miss whose upstream omitted Content-Length (feature
 * 015); cache reads always know the size. A null size is served as a chunked response.
 */
class StoredArtifact(
    val stream: InputStream,
    val sizeBytes: Long?,
)

/** An entry in a storage listing: a folder, or a file with its size and last-modified time. */
data class StorageEntry(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long? = null,
    val lastModified: Instant? = null,
)
