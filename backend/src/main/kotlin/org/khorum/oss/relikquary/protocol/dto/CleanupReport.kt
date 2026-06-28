package org.khorum.oss.relikquary.protocol.dto

/** The outcome of a cleanup run (feature 009): per-repository results plus totals. */
data class CleanupReport(
    val dryRun: Boolean,
    val itemsRemoved: Int,
    val bytesReclaimed: Long,
    val repositories: List<RepoCleanupResult>,
)

/** What cleanup removed (or, for a dry-run, would remove) in one repository. */
data class RepoCleanupResult(
    val name: String,
    val itemsRemoved: Int,
    val bytesReclaimed: Long,
)
