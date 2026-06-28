package org.khorum.oss.relikquary.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Path

/** US3: per-repository DELETE authorization over the manage API (feature 007). */
class PerRepoDeleteAuthzTest : AbstractAuthzTest() {

    companion object {
        @TempDir
        @JvmStatic
        lateinit var storageRoot: Path

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("relikquary.storage.filesystem.root") { storageRoot.toString() }
        }
    }

    private fun seedPrivate(coord: String) =
        assertEquals(201, put("/privlib/$coord", byteArrayOf(1, 2, 3), basic("alice", "pw")))

    @Test
    fun `restricted repo permits delete only for its listed user`() {
        val coord = "com/acme/d/1.0.0/d-1.0.0.jar"
        seedPrivate(coord)
        assertEquals(401, delete("/api/repositories/privlib/$coord"))
        assertEquals(403, delete("/api/repositories/privlib/$coord", basic("bob", "pw")))
        assertEquals(204, delete("/api/repositories/privlib/$coord", basic("alice", "pw")))
    }

    @Test
    fun `repo with no delete grant uses the global PUBLISH default`() {
        val coord = "com/acme/e/1.0.0/e-1.0.0.jar"
        assertEquals(201, put("/releases/$coord", byteArrayOf(7, 8, 9), basic("ci", "pw")))
        assertEquals(204, delete("/api/repositories/releases/$coord", basic("ci", "pw")))
    }
}
