package org.khorum.oss.relikquary.protocol.dto

/**
 * Read-only summary for the Dashboard (feature 016, Phase 2). [artifacts] is the total stored object
 * count and [storageBytes] the total stored size — both from the shared storage-usage snapshot, so the
 * figures match the `relikquary.storage.*` gauges and never trigger a per-request store walk.
 */
data class StatsResponse(
    val repositories: Int,
    val artifacts: Long,
    val storageBytes: Long,
)

/**
 * One aggregated artifact in the cross-repo catalog (feature 016, Phase 2): a `group:artifact` with its
 * latest version, the number of versions, and the total stored size across all of them. Derived from the
 * stored artifacts; for proxy repositories it reflects cached content only.
 */
data class CatalogEntry(
    val repository: String,
    val group: String,
    val artifact: String,
    val latestVersion: String,
    val versionCount: Int,
    val sizeBytes: Long,
)

/** A page of catalog entries with the totals needed to drive client-side paging/disclosure. */
data class CatalogResponse(
    val entries: List<CatalogEntry>,
    val page: Int,
    val pageSize: Int,
    val total: Long,
    val truncated: Boolean,
)
