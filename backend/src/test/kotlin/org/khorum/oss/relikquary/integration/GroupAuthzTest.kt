package org.khorum.oss.relikquary.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Path

/**
 * US3: group reads apply each member's READ policy with permissive-union semantics (feature 007). The
 * `grp` group has a private member (`privlib`, read = alice) and an open member (`openmirror`).
 */
class GroupAuthzTest : AbstractAuthzTest() {

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

    private val privateOnly = "com/acme/secret/1.0.0/secret-1.0.0.jar"
    private val openOnly = "com/acme/public/1.0.0/public-1.0.0.jar"

    @BeforeEach
    fun seed() {
        // Shared static storage: the first test seeds; later re-seeds of the same release coordinate
        // return 409 (immutable) and are harmless — the artifact is already present.
        put("/privlib/$privateOnly", byteArrayOf(1), basic("alice", "pw"))
        put("/openmirror/$openOnly", byteArrayOf(2), basic("ci", "pw"))
    }

    @Test
    fun `permitted user reads a private artifact through the group`() {
        assertEquals(200, get("/grp/$privateOnly", basic("alice", "pw")).statusCode())
    }

    @Test
    fun `denied user is skipped on the private member and gets 404, not 401`() {
        assertEquals(404, get("/grp/$privateOnly", basic("bob", "pw")).statusCode())
        assertEquals(404, get("/grp/$privateOnly").statusCode())
    }

    @Test
    fun `denied user still reads what an open member serves`() {
        assertEquals(200, get("/grp/$openOnly", basic("bob", "pw")).statusCode())
        assertEquals(200, get("/grp/$openOnly").statusCode())
    }
}
