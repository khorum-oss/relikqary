package org.khorum.oss.relikquary.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Path

/** US2: private repositories — read restriction over the Maven path and the browse API (feature 007). */
class PrivateRepoReadTest : AbstractAuthzTest() {

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

    private val artifact = "com/acme/lib/1.0.0/lib-1.0.0.jar"
    private val metadata = "com/acme/lib/maven-metadata.xml"

    @BeforeEach
    fun seed() {
        // alice may publish to the private repo; ci (global PUBLISH) seeds the open repo.
        put("/privlib/$artifact", byteArrayOf(1, 2, 3), basic("alice", "pw"))
        put("/privlib/$metadata", "<metadata/>".toByteArray(), basic("alice", "pw"))
        put("/releases/$artifact", byteArrayOf(4, 5, 6), basic("ci", "pw"))
    }

    @Test
    fun `private repo read is allowed only for permitted users`() {
        assertEquals(200, get("/privlib/$artifact", basic("alice", "pw")).statusCode())
        assertEquals(403, get("/privlib/$artifact", basic("bob", "pw")).statusCode())
        assertEquals(401, get("/privlib/$artifact").statusCode())
    }

    @Test
    fun `read restriction also covers metadata siblings`() {
        assertEquals(403, get("/privlib/$metadata", basic("bob", "pw")).statusCode())
        assertEquals(200, get("/privlib/$metadata", basic("alice", "pw")).statusCode())
    }

    @Test
    fun `repo with no read grant stays open to anonymous`() {
        assertEquals(200, get("/releases/$artifact").statusCode())
    }

    @Test
    fun `browse API enforces the same read policy`() {
        assertEquals(403, get("/api/repositories/privlib/contents/com/acme/lib", basic("bob", "pw")).statusCode())
        assertEquals(200, get("/api/repositories/privlib/contents/com/acme/lib", basic("alice", "pw")).statusCode())
    }

    @Test
    fun `repository list is not secret`() {
        assertEquals(200, get("/api/repositories").statusCode())
    }
}
