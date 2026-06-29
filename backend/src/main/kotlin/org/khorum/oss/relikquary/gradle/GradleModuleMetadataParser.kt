package org.khorum.oss.relikquary.gradle

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import java.io.IOException

/**
 * Parses Gradle Module Metadata (`.module`) bytes into the read-only [GradleModuleMetadata] projection
 * (feature 011). Tree-based and tolerant: the GMM `dependency.version` is an object and attribute values
 * vary in type, so fields are extracted defensively and unknown JSON is ignored — newer format versions
 * still parse the fields we present. Never throws to the caller; malformed input yields
 * [ParseResult.Unparseable]. Strictly read-only — the stored bytes are never altered.
 */
@Service
class GradleModuleMetadataParser {

    private val mapper = ObjectMapper()

    fun parse(bytes: ByteArray): ParseResult {
        if (bytes.isEmpty()) return ParseResult.Unparseable("empty module metadata")
        return try {
            val root = mapper.readTree(bytes)
            if (root == null || !root.isObject) {
                ParseResult.Unparseable("module metadata is not a JSON object")
            } else {
                ParseResult.Parsed(
                    GradleModuleMetadata(
                        formatVersion = root.str("formatVersion"),
                        component = root.get("component")?.let(::parseComponent),
                        variants = root.path("variants").takeIf { it.isArray }?.map(::parseVariant).orEmpty(),
                    ),
                )
            }
        } catch (e: IOException) {
            ParseResult.Unparseable(e.message ?: "malformed module metadata")
        }
    }

    private fun parseComponent(node: JsonNode): Component =
        Component(group = node.str("group"), module = node.str("module"), version = node.str("version"))

    private fun parseVariant(node: JsonNode): Variant =
        Variant(
            name = node.str("name").orEmpty(),
            attributes = node.path("attributes").takeIf { it.isObject }
                ?.properties()?.associate { (k, v) -> k to v.asText() }.orEmpty(),
            capabilities = node.path("capabilities").takeIf { it.isArray }
                ?.map { Capability(it.str("group"), it.str("name"), it.str("version")) }.orEmpty(),
            dependencies = node.path("dependencies").takeIf { it.isArray }
                ?.map { Dependency(it.str("group"), it.str("module"), versionOf(it.get("version"))) }.orEmpty(),
            files = node.path("files").takeIf { it.isArray }
                ?.map { ModuleFile(it.str("name"), it.str("url"), it.get("size")?.takeIf(JsonNode::isNumber)?.asLong(), it.str("sha256")) }
                .orEmpty(),
        )

    /** GMM `version` is an object like `{ "requires": "1.0" }`; tolerate a bare string too. */
    private fun versionOf(node: JsonNode?): String? = when {
        node == null || node.isNull -> null
        node.isTextual -> node.asText()
        else -> node.str("requires") ?: node.str("strictly") ?: node.str("prefers")
    }

    private fun JsonNode.str(field: String): String? =
        get(field)?.takeIf { it.isValueNode && !it.isNull }?.asText()
}
