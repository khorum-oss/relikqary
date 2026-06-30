package org.khorum.oss.relikquary.unit

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.khorum.oss.relikquary.proxy.TeeInputStream
import org.khorum.oss.relikquary.storage.ArtifactWrite
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Unit coverage for the tee's promote-on-success / abort-on-failure contract (feature 015): EOF ⇒
 * commit with byte-identical mirror, close-before-EOF ⇒ abort, read error ⇒ abort, double-close safe.
 */
class TeeInputStreamTest {

    private class RecordingWrite : ArtifactWrite {
        val buffer = ByteArrayOutputStream()
        override val sink: OutputStream get() = buffer
        var commits = 0
        var aborts = 0
        override fun commit(): Long {
            commits++
            return buffer.size().toLong()
        }
        override fun abort() {
            aborts++
        }
        override fun close() = Unit
    }

    @Test
    fun `reading to EOF commits a byte-identical mirror`() {
        val src = "hello streaming world".toByteArray()
        val write = RecordingWrite()
        val tee = TeeInputStream(ByteArrayInputStream(src), write)

        val delivered = tee.readBytes()
        tee.close()

        assertArrayEquals(src, delivered)
        assertArrayEquals(src, write.buffer.toByteArray())
        assertEquals(1, write.commits)
        assertEquals(0, write.aborts)
    }

    @Test
    fun `EOF with fewer bytes than the declared length aborts`() {
        // Simulates a truncated fixed-length body surfaced as a clean EOF rather than an error.
        val write = RecordingWrite()
        val tee = TeeInputStream(ByteArrayInputStream("partial".toByteArray()), write, expectedLength = 999L)

        tee.readBytes()
        tee.close()

        assertEquals(0, write.commits)
        assertEquals(1, write.aborts)
    }

    @Test
    fun `EOF matching the declared length commits`() {
        val src = "exactly-this".toByteArray()
        val write = RecordingWrite()
        val tee = TeeInputStream(ByteArrayInputStream(src), write, expectedLength = src.size.toLong())

        tee.readBytes()
        tee.close()

        assertEquals(1, write.commits)
        assertEquals(0, write.aborts)
    }

    @Test
    fun `closing before EOF aborts`() {
        val write = RecordingWrite()
        val tee = TeeInputStream(ByteArrayInputStream("abcdef".toByteArray()), write)

        assertEquals('a'.code, tee.read())
        tee.close()

        assertEquals(0, write.commits)
        assertEquals(1, write.aborts)
    }

    @Test
    fun `a read error aborts`() {
        val write = RecordingWrite()
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("upstream reset")
        }
        val tee = TeeInputStream(failing, write)

        assertThrows(IOException::class.java) { tee.read() }
        tee.close()

        assertEquals(0, write.commits)
        assertEquals(1, write.aborts)
    }

    @Test
    fun `double close is safe and resolves once`() {
        val write = RecordingWrite()
        val tee = TeeInputStream(ByteArrayInputStream(ByteArray(0)), write)

        assertEquals(-1, tee.read())
        tee.close()
        tee.close()

        assertEquals(1, write.commits)
        assertEquals(0, write.aborts)
    }
}
