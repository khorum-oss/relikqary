package org.khorum.oss.relikquary.unit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.khorum.oss.relikquary.gradle.GradleModuleMetadataParser
import org.khorum.oss.relikquary.gradle.ParseResult

/**
 * Tolerant parsing of Gradle Module Metadata (feature 011, FR-009): a well-formed `.module` parses to
 * variants with attributes/capabilities/dependencies/files; unknown fields are ignored; malformed/empty
 * input degrades to [ParseResult.Unparseable] without throwing.
 */
class GradleModuleMetadataParserTest {

    private val parser = GradleModuleMetadataParser()

    private val wellFormed = """
        {
          "formatVersion": "1.1",
          "component": { "group": "com.acme", "module": "widget", "version": "1.2.3" },
          "variants": [
            {
              "name": "apiElements",
              "attributes": { "org.gradle.usage": "java-api", "org.gradle.category": "library" },
              "capabilities": [ { "group": "com.acme", "name": "widget-extra", "version": "1.2.3" } ],
              "dependencies": [ { "group": "com.google.guava", "module": "guava", "version": { "requires": "33.0.0-jre" } } ],
              "files": [ { "name": "widget-1.2.3.jar", "url": "widget-1.2.3.jar", "size": 1234, "sha256": "abc123" } ]
            }
          ],
          "unknownTopLevelField": { "ignored": true }
        }
    """.trimIndent()

    @Test
    fun `parses variants with attributes, capabilities, dependencies, and files`() {
        val result = parser.parse(wellFormed.toByteArray())

        val parsed = assertInstanceOf(ParseResult.Parsed::class.java, result).metadata
        assertEquals("1.1", parsed.formatVersion)
        assertEquals("com.acme", parsed.component?.group)
        assertEquals(1, parsed.variants.size)
        val variant = parsed.variants.first()
        assertEquals("apiElements", variant.name)
        assertEquals("java-api", variant.attributes["org.gradle.usage"])
        assertEquals("widget-extra", variant.capabilities.first().name)
        assertEquals("guava", variant.dependencies.first().module)
        assertEquals("33.0.0-jre", variant.dependencies.first().version)
        assertEquals("widget-1.2.3.jar", variant.files.first().name)
        assertEquals(1234L, variant.files.first().size)
        assertEquals("abc123", variant.files.first().sha256)
    }

    @Test
    fun `malformed json degrades gracefully`() {
        assertInstanceOf(ParseResult.Unparseable::class.java, parser.parse("{ not json".toByteArray()))
    }

    @Test
    fun `empty bytes degrade gracefully`() {
        assertInstanceOf(ParseResult.Unparseable::class.java, parser.parse(ByteArray(0)))
    }

    @Test
    fun `a json non-object degrades gracefully`() {
        assertTrue(parser.parse("[]".toByteArray()) is ParseResult.Unparseable)
    }
}
