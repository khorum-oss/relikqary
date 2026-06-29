package org.khorum.oss.relikquary.gradle

/**
 * A read-only projection of the parts of a Gradle Module Metadata (`.module`) file the browse UI
 * presents (feature 011). Parsed on demand from the stored bytes; never written back. Only the fields
 * the UI shows are modelled — unknown JSON is ignored so newer format versions still parse.
 */
data class GradleModuleMetadata(
    val formatVersion: String?,
    val component: Component?,
    val variants: List<Variant>,
)

/** The published component coordinate from the module's `component` block. */
data class Component(
    val group: String?,
    val module: String?,
    val version: String?,
)

/** A consumable view of the module (e.g. `apiElements`, `runtimeElements`) with its selection model. */
data class Variant(
    val name: String,
    val attributes: Map<String, String>,
    val capabilities: List<Capability>,
    val dependencies: List<Dependency>,
    val files: List<ModuleFile>,
)

data class Capability(val group: String?, val name: String?, val version: String?)

data class Dependency(val group: String?, val module: String?, val version: String?)

data class ModuleFile(val name: String?, val url: String?, val size: Long?, val sha256: String?)

/** The outcome of parsing a `.module`: either the parsed model or a graceful, non-throwing failure. */
sealed interface ParseResult {
    data class Parsed(val metadata: GradleModuleMetadata) : ParseResult

    data class Unparseable(val reason: String) : ParseResult
}
