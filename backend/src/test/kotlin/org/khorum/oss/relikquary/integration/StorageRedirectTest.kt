package org.khorum.oss.relikquary.integration

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.khorum.oss.relikquary.RelikquaryApplication
import org.khorum.oss.relikquary.storage.ArtifactStorage
import org.springframework.boot.SpringApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random

/**
 * Storage location is fully configuration-driven (FR-007, SC-005): booting the same application with a
 * different `relikquary.storage.filesystem.root` persists artifacts to the new location, with no code
 * change and no writes to the previous location.
 */
class StorageRedirectTest {

    private val key = "com/example/redir/1.0.0/redir-1.0.0.jar"

    private fun boot(root: Path): org.springframework.context.ConfigurableApplicationContext =
        SpringApplication.run(
            RelikquaryApplication::class.java,
            "--server.port=0",
            "--relikquary.storage.filesystem.root=$root",
        )

    @Test
    fun `persists artifacts to the configured location`(@TempDir locationA: Path, @TempDir locationB: Path) {
        val bytesA = Random.nextBytes(256)
        boot(locationA).use { ctx ->
            ctx.getBean(ArtifactStorage::class.java).write(key, bytesA.inputStream())
        }
        assertTrue(Files.isRegularFile(locationA.resolve(key))) { "artifact not stored under configured location A" }

        val bytesB = Random.nextBytes(256)
        boot(locationB).use { ctx ->
            ctx.getBean(ArtifactStorage::class.java).write(key, bytesB.inputStream())
        }
        assertTrue(Files.isRegularFile(locationB.resolve(key))) { "artifact not stored under reconfigured location B" }

        // Reconfiguring to B did not write into A: each location holds only its own artifact.
        assertArrayEquals(bytesA, Files.readAllBytes(locationA.resolve(key))) { "location A changed after redirect to B" }
        assertArrayEquals(bytesB, Files.readAllBytes(locationB.resolve(key))) { "location B holds unexpected bytes" }
    }
}
