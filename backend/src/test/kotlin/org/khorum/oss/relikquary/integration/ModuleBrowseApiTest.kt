package org.khorum.oss.relikquary.integration

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Path

/**
 * Browse-API surface for Gradle modules (feature 011, FR-006/FR-009, SC-005/SC-006): coordinate-aware
 * contents, the parsed `/module` endpoint (graceful on malformed), and that the new endpoint obeys the
 * same per-repository READ policy as the rest of the browse API. Uses the `authz` profile (open
 * `releases`, private `privlib`).
 */
class ModuleBrowseApiTest : AbstractAuthzTest() {

    private val mapper = ObjectMapper()
    private val ci = basic("ci", "pw")

    @Test
    fun `contents exposes the coordinate and a module ref, and the module endpoint parses variants`() {
        assertEquals(201, put("/releases/com/acme/widget/1.2.3/widget-1.2.3.module", WELL_FORMED.toByteArray(), ci))
        assertEquals(201, put("/releases/com/acme/widget/1.2.3/widget-1.2.3.jar", byteArrayOf(1, 2, 3), ci))

        val contents = mapper.readTree(get("/api/repositories/releases/contents/com/acme/widget/1.2.3").body())
        assertEquals("com.acme", contents.path("coordinate").path("group").asText())
        assertEquals("widget", contents.path("coordinate").path("artifact").asText())
        assertEquals("1.2.3", contents.path("coordinate").path("version").asText())
        assertTrue(contents.path("module").path("path").asText().endsWith("widget-1.2.3.module"))

        val module = get("/api/repositories/releases/module/com/acme/widget/1.2.3/widget-1.2.3.module")
        assertEquals(200, module.statusCode())
        val body = mapper.readTree(module.body())
        assertTrue(body.path("parseable").asBoolean())
        assertEquals("apiElements", body.path("variants").get(0).path("name").asText())
        assertEquals("widget-extra", body.path("variants").get(0).path("capabilities").get(0).path("name").asText())
    }

    @Test
    fun `a malformed module degrades to parseable false and the coordinate still lists`() {
        assertEquals(201, put("/releases/com/acme/broken/1.0/broken-1.0.module", "{ not json".toByteArray(), ci))

        val module = get("/api/repositories/releases/module/com/acme/broken/1.0/broken-1.0.module")
        assertEquals(200, module.statusCode())
        assertFalse(mapper.readTree(module.body()).path("parseable").asBoolean())
        assertEquals(200, get("/api/repositories/releases/contents/com/acme/broken/1.0").statusCode())
    }

    @Test
    fun `a maven-only coordinate has a coordinate but no module ref`() {
        assertEquals(201, put("/releases/com/acme/plain/1.0/plain-1.0.jar", byteArrayOf(4, 5), ci))
        assertEquals(201, put("/releases/com/acme/plain/1.0/plain-1.0.pom", byteArrayOf(6), ci))

        val contents = mapper.readTree(get("/api/repositories/releases/contents/com/acme/plain/1.0").body())
        assertEquals("plain", contents.path("coordinate").path("artifact").asText())
        assertTrue(contents.path("module").isNull)
    }

    @Test
    fun `the module endpoint obeys the per-repository read policy`() {
        assertEquals(201, put("/privlib/com/acme/secret/1.0/secret-1.0.module", WELL_FORMED.toByteArray(), basic("alice", "pw")))
        val path = "/api/repositories/privlib/module/com/acme/secret/1.0/secret-1.0.module"

        assertEquals(401, get(path).statusCode())
        assertEquals(403, get(path, basic("bob", "pw")).statusCode())
        assertEquals(200, get(path, basic("alice", "pw")).statusCode())
    }

    companion object {
        @TempDir
        @JvmStatic
        lateinit var storageRoot: Path

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("relikquary.storage.filesystem.root") { storageRoot.toString() }
        }

        private val WELL_FORMED = """
            {
              "formatVersion": "1.1",
              "component": { "group": "com.acme", "module": "widget", "version": "1.2.3" },
              "variants": [
                {
                  "name": "apiElements",
                  "attributes": { "org.gradle.usage": "java-api" },
                  "capabilities": [ { "group": "com.acme", "name": "widget-extra", "version": "1.2.3" } ],
                  "dependencies": [ { "group": "com.google.guava", "module": "guava", "version": { "requires": "33.0.0-jre" } } ],
                  "files": [ { "name": "widget-1.2.3.jar", "url": "widget-1.2.3.jar", "size": 1234, "sha256": "abc" } ]
                }
              ]
            }
        """.trimIndent()
    }
}
