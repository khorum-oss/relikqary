package org.khorum.oss.relikquary.protocol.dto

import java.time.Instant

/** A configured repository, as exposed by the browse API. */
data class RepositorySummary(
    val name: String,
    val type: String,
    val kind: String,
)

/** An entry in a directory listing: a folder, or a file with size + last-modified. */
data class ListingEntry(
    val name: String,
    val kind: String,
    val size: Long? = null,
    val lastModified: Instant? = null,
)

/** A Maven/Gradle artifact coordinate derived from a version-directory browse path (feature 011). */
data class Coordinate(
    val group: String,
    val artifact: String,
    val version: String,
)

/** A reference to a recognized Gradle Module Metadata file for the browsed coordinate (feature 011). */
data class ModuleRef(
    val path: String,
)

/**
 * The contents directly under a repository path. When the path is a coordinate's version directory,
 * [coordinate] is populated (so the UI can render consume snippets); when that directory also contains a
 * recognized `.module`, [module] points at it (so the UI can badge it and open the module view).
 */
data class ContentsResponse(
    val repository: String,
    val path: String,
    val entries: List<ListingEntry>,
    val coordinate: Coordinate? = null,
    val module: ModuleRef? = null,
)

/**
 * Parsed Gradle Module Metadata for the browse UI (feature 011). [parseable] is false when the `.module`
 * exists but could not be parsed (graceful degrade); the bytes are never altered.
 */
data class ModuleMetadataResponse(
    val repository: String,
    val path: String,
    val parseable: Boolean,
    val component: org.khorum.oss.relikquary.gradle.Component? = null,
    val variants: List<org.khorum.oss.relikquary.gradle.Variant> = emptyList(),
)

/** Details of a single stored file, including any sibling checksum values. */
data class FileDetails(
    val repository: String,
    val path: String,
    val size: Long,
    val lastModified: Instant?,
    val checksums: Map<String, String>,
    val downloadUrl: String,
)
