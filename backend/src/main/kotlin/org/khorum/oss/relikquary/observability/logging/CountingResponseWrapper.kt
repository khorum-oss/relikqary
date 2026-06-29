package org.khorum.oss.relikquary.observability.logging

import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper

/**
 * Counts response-body bytes for the request log (feature 010) without buffering the body — artifacts can
 * be large, so the stream is wrapped, not captured. Counts bytes written through the servlet output
 * stream (the path artifact responses use); when nothing was streamed (e.g. a writer-based or empty
 * response) it falls back to the `Content-Length` header.
 */
class CountingResponseWrapper(response: HttpServletResponse) : HttpServletResponseWrapper(response) {

    private var countingStream: CountingServletOutputStream? = null

    override fun getOutputStream(): ServletOutputStream {
        val existing = countingStream
        if (existing != null) return existing
        return CountingServletOutputStream(super.getOutputStream()).also { countingStream = it }
    }

    /** Bytes written to the output stream, or the declared `Content-Length` when nothing was streamed. */
    val bytesWritten: Long
        get() {
            val counted = countingStream?.count ?: 0L
            if (counted > 0L) return counted
            return getHeader("Content-Length")?.toLongOrNull() ?: 0L
        }

    private class CountingServletOutputStream(private val delegate: ServletOutputStream) : ServletOutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            delegate.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            count += len
        }

        override fun isReady(): Boolean = delegate.isReady

        override fun setWriteListener(writeListener: WriteListener?) = delegate.setWriteListener(writeListener)
    }
}
