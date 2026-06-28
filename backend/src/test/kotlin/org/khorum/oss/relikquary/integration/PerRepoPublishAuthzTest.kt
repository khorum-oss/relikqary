package org.khorum.oss.relikquary.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Path
import kotlin.random.Random

/** US1: per-repository publish control (feature 007). */
class PerRepoPublishAuthzTest : AbstractAuthzTest() {

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

    private fun coord(v: String) = "com/acme/x/$v/x-$v.jar"

    @Test
    fun `restricted repo permits only its listed publisher`() {
        assertEquals(201, put("/privlib/${coord("1.0.0")}", Random.nextBytes(32), basic("alice", "pw")))
        assertEquals(403, put("/privlib/${coord("1.0.1")}", Random.nextBytes(32), basic("bob", "pw")))
        assertEquals(401, put("/privlib/${coord("1.0.2")}", Random.nextBytes(32)))
    }

    @Test
    fun `repo with no publish grant uses the global PUBLISH default`() {
        assertEquals(201, put("/releases/${coord("2.0.0")}", Random.nextBytes(32), basic("ci", "pw")))
        assertEquals(403, put("/releases/${coord("2.0.1")}", Random.nextBytes(32), basic("bob", "pw")))
    }

    @Test
    fun `publishing to a group is rejected with 405 regardless of authz`() {
        assertEquals(405, put("/grp/${coord("3.0.0")}", Random.nextBytes(32), basic("ci", "pw")))
    }
}
