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

/** The contents directly under a repository path. */
data class ContentsResponse(
    val repository: String,
    val path: String,
    val entries: List<ListingEntry>,
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
