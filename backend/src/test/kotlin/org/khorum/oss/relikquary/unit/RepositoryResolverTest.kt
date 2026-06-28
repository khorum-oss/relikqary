package org.khorum.oss.relikquary.unit

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.khorum.oss.relikquary.config.RepositoryProperties
import org.khorum.oss.relikquary.config.RepositoryProperties.Repo
import org.khorum.oss.relikquary.coordinate.RepositoryPath
import org.khorum.oss.relikquary.proxy.UpstreamClient
import org.khorum.oss.relikquary.proxy.UpstreamResponse
import org.khorum.oss.relikquary.repository.RepositoryKind
import org.khorum.oss.relikquary.repository.RepositoryRegistry
import org.khorum.oss.relikquary.repository.RepositoryResolver
import org.khorum.oss.relikquary.repository.RepositoryType
import org.khorum.oss.relikquary.repository.Resolution
import org.khorum.oss.relikquary.storage.ArtifactStorage
import org.khorum.oss.relikquary.storage.StoredArtifact
import java.io.ByteArrayInputStream

class RepositoryResolverTest {

    private val registry = RepositoryRegistry(
        RepositoryProperties(
            listOf(
                Repo(name = "releases", type = RepositoryType.RELEASE),
                Repo(name = "central", kind = RepositoryKind.PROXY, remoteUrl = "https://up.example/m2"),
                Repo(name = "public", kind = RepositoryKind.GROUP, members = listOf("releases", "central")),
            ),
        ),
    )
    private val storage: ArtifactStorage = mockk(relaxed = false)
    private val upstream: UpstreamClient = mockk(relaxed = false)
    private val resolver = RepositoryResolver(registry, storage, upstream)

    private val jar = RepositoryPath.of("com/example/widget/1.0.0/widget-1.0.0.jar")
    private val metadata = RepositoryPath.of("com/example/widget/maven-metadata.xml")

    private fun artifact() = StoredArtifact(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3)

    @Test
    fun `hosted hit returns the stored artifact`() {
        every { storage.openRead("releases/${jar.key}") } returns artifact()
        assertInstanceOf(Resolution.Hit::class.java, resolver.resolve("releases", jar))
    }

    @Test
    fun `hosted miss returns Miss`() {
        every { storage.openRead("releases/${jar.key}") } returns null
        assertEquals(Resolution.Miss, resolver.resolve("releases", jar))
    }

    @Test
    fun `proxy cache hit serves locally without contacting upstream`() {
        every { storage.openRead("central/${jar.key}") } returns artifact()
        assertInstanceOf(Resolution.Hit::class.java, resolver.resolve("central", jar))
        verify(exactly = 0) { upstream.fetch(any(), any()) }
    }

    @Test
    fun `proxy cache miss fetches, caches, then serves`() {
        every { storage.openRead("central/${jar.key}") } returnsMany listOf(null, artifact())
        every { upstream.fetch(any(), jar.key) } returns
            UpstreamResponse.Found(ByteArrayInputStream(byteArrayOf(9)), 1)
        every { storage.write("central/${jar.key}", any()) } returns 1

        assertInstanceOf(Resolution.Hit::class.java, resolver.resolve("central", jar))
        verify(exactly = 1) { storage.write("central/${jar.key}", any()) }
    }

    @Test
    fun `proxy metadata is pass-through and never cached`() {
        every { upstream.fetch(any(), metadata.key) } returns
            UpstreamResponse.Found(ByteArrayInputStream(byteArrayOf(7, 7)), 2)

        assertInstanceOf(Resolution.Hit::class.java, resolver.resolve("central", metadata))
        verify(exactly = 0) { storage.openRead("central/${metadata.key}") }
        verify(exactly = 0) { storage.write(any(), any()) }
    }

    @Test
    fun `proxy upstream not-found returns Miss and caches nothing`() {
        every { storage.openRead("central/${jar.key}") } returns null
        every { upstream.fetch(any(), jar.key) } returns UpstreamResponse.NotFound
        assertEquals(Resolution.Miss, resolver.resolve("central", jar))
        verify(exactly = 0) { storage.write(any(), any()) }
    }

    @Test
    fun `proxy upstream error returns UpstreamError`() {
        every { storage.openRead("central/${jar.key}") } returns null
        every { upstream.fetch(any(), jar.key) } returns UpstreamResponse.Error
        assertEquals(Resolution.UpstreamError, resolver.resolve("central", jar))
    }

    @Test
    fun `group returns the first member that hits`() {
        every { storage.openRead("releases/${jar.key}") } returns artifact()
        assertInstanceOf(Resolution.Hit::class.java, resolver.resolve("public", jar))
        verify(exactly = 0) { upstream.fetch(any(), any()) }
    }

    @Test
    fun `group falls through to a later proxy member`() {
        every { storage.openRead("releases/${jar.key}") } returns null
        every { storage.openRead("central/${jar.key}") } returnsMany listOf(null, artifact())
        every { upstream.fetch(any(), jar.key) } returns
            UpstreamResponse.Found(ByteArrayInputStream(byteArrayOf(5)), 1)
        every { storage.write("central/${jar.key}", any()) } returns 1
        assertInstanceOf(Resolution.Hit::class.java, resolver.resolve("public", jar))
    }

    @Test
    fun `group with all members missing returns Miss`() {
        every { storage.openRead("releases/${jar.key}") } returns null
        every { storage.openRead("central/${jar.key}") } returns null
        every { upstream.fetch(any(), jar.key) } returns UpstreamResponse.NotFound
        assertEquals(Resolution.Miss, resolver.resolve("public", jar))
    }

    @Test
    fun `group reports UpstreamError when a member errors and none hit`() {
        every { storage.openRead("releases/${jar.key}") } returns null
        every { storage.openRead("central/${jar.key}") } returns null
        every { upstream.fetch(any(), jar.key) } returns UpstreamResponse.Error
        assertEquals(Resolution.UpstreamError, resolver.resolve("public", jar))
    }

    @Test
    fun `resolution hit carries a readable stream`() {
        every { storage.openRead("releases/${jar.key}") } returns artifact()
        val hit = resolver.resolve("releases", jar) as Resolution.Hit
        assertTrue(hit.artifact.sizeBytes == 3L)
    }
}
