package org.khorum.oss.relikquary.storage

import java.io.Closeable
import java.io.OutputStream

/**
 * A pending, not-yet-promoted cache write (feature 015). Bytes written to [sink] are invisible at the
 * target key until [commit]; [abort] — or [close] without a prior commit — discards them. Thus only a
 * fully, successfully transferred artifact ever becomes a cache entry, and a truncated transfer never
 * leaves a partial entry. Implementations preserve bytes exactly (constitution IV).
 *
 * [commit] and [abort] are mutually exclusive and each idempotent; [close] is always safe to call.
 */
interface ArtifactWrite : Closeable {

    /** The stream mirrored bytes are written to during the transfer. */
    val sink: OutputStream

    /** Atomically promotes the written bytes to the target key. Returns bytes written. Call once, on success. */
    fun commit(): Long

    /** Discards the pending bytes; no cache entry is created. */
    fun abort()

    /** Releases resources, aborting if neither [commit] nor [abort] has run (never leaks a temp artifact). */
    override fun close()
}
