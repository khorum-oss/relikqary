package org.khorum.oss.relikquary.proxy

import org.khorum.oss.relikquary.storage.ArtifactWrite
import java.io.IOException
import java.io.InputStream

/**
 * Mirrors every byte read from [upstream] into [pending].sink and resolves the pending cache write by
 * how reading ends (feature 015): underlying EOF with no read error -> [ArtifactWrite.commit]; closed
 * before EOF, or a read failure -> [ArtifactWrite.abort]. So a truncated or client-aborted transfer
 * never becomes a cache entry, while a fully read transfer is cached as a byte-for-byte copy.
 *
 * The caller (the HTTP layer streaming to the client) is the single consumer; [close] is idempotent.
 *
 * When [expectedLength] is non-null (the upstream declared a Content-Length), a commit additionally
 * requires the mirrored byte count to equal it — so a truncated fixed-length body that the HTTP client
 * surfaces as a premature EOF (rather than an error) is still rejected and never cached.
 */
class TeeInputStream(
    private val upstream: InputStream,
    private val pending: ArtifactWrite,
    private val expectedLength: Long? = null,
) : InputStream() {

    private var eofReached = false
    private var failed = false
    private var resolved = false
    private var mirrored = 0L

    override fun read(): Int {
        val b = readUpstream { upstream.read() }
        if (b < 0) {
            eofReached = true
        } else {
            pending.sink.write(b)
            mirrored++
        }
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = readUpstream { upstream.read(b, off, len) }
        if (n < 0) {
            eofReached = true
        } else if (n > 0) {
            pending.sink.write(b, off, n)
            mirrored += n
        }
        return n
    }

    override fun available(): Int = upstream.available()

    override fun close() {
        if (resolved) return
        resolved = true
        try {
            if (completedFully()) {
                pending.sink.flush()
                pending.commit()
            } else {
                pending.abort()
            }
        } finally {
            runCatching { pending.close() }
            upstream.close()
        }
    }

    private fun completedFully(): Boolean =
        eofReached && !failed && (expectedLength == null || mirrored == expectedLength)

    private inline fun readUpstream(read: () -> Int): Int =
        try {
            read()
        } catch (e: IOException) {
            failed = true
            throw e
        }
}
