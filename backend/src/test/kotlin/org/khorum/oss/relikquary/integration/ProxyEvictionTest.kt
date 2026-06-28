package org.khorum.oss.relikquary.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.khorum.oss.relikquary.cleanup.CleanupService
import org.khorum.oss.relikquary.storage.ArtifactStorage
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path

/** US2: proxy cache eviction removes cached artifacts; a later request safely re-fetches (feature 009). */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["relikquary.security.enabled=false"],
)
@ActiveProfiles("cleanup")
class ProxyEvictionTest {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var cleanup: CleanupService

    @Autowired
    lateinit var storage: ArtifactStorage

    private val http: HttpClient = HttpClient.newHttpClient()

    companion object {
        private val stub = StubUpstream().start()

        @TempDir
        @JvmStatic
        lateinit var storageRoot: Path

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("relikquary.storage.filesystem.root") { storageRoot.toString() }
            registry.add("RELIKQUARY_TEST_UPSTREAM") { stub.baseUrl }
        }
    }

    private val coord = "com/acme/dep/1.0.0/dep-1.0.0.jar"

    private fun get(path: String): Int =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.discarding(),
        ).statusCode()

    @Test
    fun `eviction removes cached artifacts and a later request re-fetches`() {
        stub.seed(coord, "upstream-bytes".toByteArray())

        // Resolve through the proxy ⇒ cached locally.
        assertEquals(200, get("/cacheproxy/$coord"))
        assertTrue(storage.exists("cacheproxy/$coord"))

        // Cleanup with maxAge=PT0S evicts everything cached before now.
        val report = cleanup.run(dryRun = false)
        assertTrue(report.itemsRemoved >= 1)
        assertFalse(storage.exists("cacheproxy/$coord"))

        // A later request transparently re-fetches and re-caches (FR-005).
        assertEquals(200, get("/cacheproxy/$coord"))
        assertTrue(storage.exists("cacheproxy/$coord"))
    }
}
