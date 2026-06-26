package org.khorum.oss.relikqary.storage

import java.io.InputStream

/**
 * Stores and serves artifact files keyed by their repository-layout path. Implementations MUST
 * preserve bytes exactly (FR-003) and never alter, re-encode, or re-checksum stored content.
 */
interface ArtifactStorage {

    /** Whether a regular file is stored at [key]. */
    fun exists(key: String): Boolean

    /** Opens the stored bytes at [key], or returns null if absent. The caller closes the stream. */
    fun openRead(key: String): StoredArtifact?

    /** Persists [content] at [key], replacing any existing content atomically. Returns bytes written. */
    fun write(key: String, content: InputStream): Long
}

/** A readable handle to a stored artifact and its size in bytes. */
class StoredArtifact(
    val stream: InputStream,
    val sizeBytes: Long,
)
