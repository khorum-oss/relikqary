package org.khorum.oss.relikquary.integration

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.khorum.oss.relikquary.config.StorageProperties
import org.khorum.oss.relikquary.storage.ArtifactStorage
import org.khorum.oss.relikquary.storage.FilesystemArtifactStorage
import org.khorum.oss.relikquary.storage.StoredArtifact
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Streaming proxy cache, happy paths (feature 015, US1/US3): a cold-cache miss streams the upstream
 * bytes to the client while caching, serves byte-identically, serves the second request from cache,
 * never caches metadata, overlaps client delivery with the upstream transfer, and uses no post-write
 * re-read (the served stream IS the fetched stream).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["relikquary.security.enabled=false"],
)
class ProxyStreamingCacheIT {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var storage: ArtifactStorage

    private val http: HttpClient = HttpClient.newHttpClient()

    /** Delegating storage that counts openRead per key, to prove the miss path does no second read. */
    class CountingStorage(private val delegate: ArtifactStorage) : ArtifactStorage by delegate {
        val openReads = ConcurrentHashMap<String, AtomicInteger>()
        override fun openRead(key: String): StoredArtifact? {
            openReads.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
            return delegate.openRead(key)
        }
    }

    @TestConfiguration
    class SpyStorageConfig {
        @Bean
        @Primary
        fun countingStorage(props: StorageProperties): ArtifactStorage =
            CountingStorage(FilesystemArtifactStorage(props))
    }

    companion object {
        private const val PREFIX = 4096
        private val stub = StubUpstream().start()

        @TempDir
        @JvmStatic
        lateinit var storageRoot: Path

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("relikquary.storage.filesystem.root") { storageRoot.toString() }
            registry.add("RELIKQUARY_MAVEN_CENTRAL_URL") { stub.baseUrl }
        }
    }

    private fun getBytes(path: String): HttpResponse<ByteArray> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

    @Test
    fun `cold-cache miss streams byte-identical artifact, caches it, and serves the second from cache`() {
        val coord = "com/acme/stream/1.0.0/stream-1.0.0.jar"
        val bytes = "the-real-upstream-jar-bytes".toByteArray()
        stub.seed(coord, bytes)

        val first = getBytes("/maven-central/$coord")
        assertEquals(200, first.statusCode())
        assertArrayEquals(bytes, first.body())

        // Drop it upstream: only a real cache entry can satisfy the second request byte-identically.
        stub.remove(coord)
        val second = getBytes("/maven-central/$coord")
        assertEquals(200, second.statusCode())
        assertArrayEquals(bytes, second.body())
    }

    @Test
    fun `a single cold miss reads the cache key once (no post-write re-read)`() {
        val coord = "com/acme/noreread/1.0.0/noreread-1.0.0.jar"
        stub.seed(coord, "noreread-bytes".toByteArray())

        assertEquals(200, getBytes("/maven-central/$coord").statusCode())

        // Exactly one openRead — the miss probe. The served stream is the fetched tee, not a re-read.
        val counting = storage as CountingStorage
        assertEquals(1, counting.openReads["maven-central/$coord"]?.get())
    }

    @Test
    fun `maven-metadata is still not cached after the streaming rewrite`() {
        val meta = "com/acme/stream/maven-metadata.xml"
        stub.seed(meta, "<metadata/>".toByteArray())
        assertEquals(200, getBytes("/maven-central/$meta").statusCode())

        stub.remove(meta)
        assertEquals(404, getBytes("/maven-central/$meta").statusCode())
    }

    @Test
    fun `client receives bytes before the upstream transfer completes`() {
        val coord = "com/acme/slow/1.0.0/slow-1.0.0.jar"
        // A large first half so the server flushes well past any response buffer before the gate blocks.
        val first = ByteArray(64 * 1024) { 'A'.code.toByte() }
        val rest = ByteArray(16 * 1024) { 'B'.code.toByte() }
        val release = CountDownLatch(1)
        stub.seedGated(coord, first, rest, release)

        val response = http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/maven-central/$coord")).GET().build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        assertEquals(200, response.statusCode())

        response.body().use { body ->
            // A flushed prefix must arrive while the upstream is still blocked (gate not released) —
            // proving the client receives bytes before the upstream completes. With the old buffer-then-
            // serve path the upstream would block before any byte reached the client and this would hang.
            val prefix = ByteArray(PREFIX)
            assertTimeoutPreemptively(Duration.ofSeconds(10)) { readFully(body, prefix) }
            assertTrue(prefix.all { it == 'A'.code.toByte() })

            release.countDown()

            val remainder = body.readBytes()
            val delivered = prefix + remainder
            assertEquals((first.size + rest.size), delivered.size)
            assertArrayEquals(first + rest, delivered)
        }
    }

    private fun readFully(input: java.io.InputStream, into: ByteArray) {
        var off = 0
        while (off < into.size) {
            val n = input.read(into, off, into.size - off)
            check(n >= 0) { "unexpected EOF at $off of ${into.size}" }
            off += n
        }
    }

    @Test
    fun `the cached artifact equals the upstream bytes on disk`() {
        val coord = "com/acme/identity/2.0.0/identity-2.0.0.jar"
        val bytes = ByteArray(4096) { (it % 256).toByte() }
        stub.seed(coord, bytes)

        assertEquals(200, getBytes("/maven-central/$coord").statusCode())

        val cached = (storage as CountingStorage).openRead("maven-central/$coord")
        assertTrue(cached != null)
        assertArrayEquals(bytes, cached!!.stream.use { it.readBytes() })
    }
}
