package org.khorum.oss.relikquary.catalog

import org.khorum.oss.relikquary.protocol.dto.CatalogEntry
import org.khorum.oss.relikquary.repository.RepositoryRegistry
import org.khorum.oss.relikquary.security.Action
import org.khorum.oss.relikquary.security.RepositoryAuthorizer
import org.khorum.oss.relikquary.storage.ArtifactStorage
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Aggregates stored artifacts into a cross-repo catalog (feature 016, Phase 2): one [CatalogEntry] per
 * `group:artifact` in a repository, carrying its latest version, version count, and total size. Computed
 * from [ArtifactStorage.walk] over each repository's artifact tree — no new stored state. Only
 * repositories the caller may READ are included, so the catalog never leaks a private repo's coordinates.
 * Group repositories own no storage, so they naturally contribute nothing.
 */
@Service
class CatalogService(
    private val registry: RepositoryRegistry,
    private val storage: ArtifactStorage,
    private val authorizer: RepositoryAuthorizer,
) {

    /** All catalog entries across the readable repositories, optionally scoped to one [repoFilter]. */
    fun entries(repoFilter: String?, authentication: Authentication?): List<CatalogEntry> =
        registry.all()
            .filter { repoFilter == null || it.name == repoFilter }
            .filter { authorizer.permits(it, Action.READ, authentication) }
            .flatMap { entriesForRepo(it.name) }

    private fun entriesForRepo(repo: String): List<CatalogEntry> {
        val files = storage.walk(repo)
        if (files.isEmpty()) return emptyList()
        val byCoordinate = HashMap<Pair<String, String>, Accumulator>()
        files.groupBy { it.key.substringBeforeLast('/', "") }.forEach { (dir, dirFiles) ->
            val coordinate = coordinateOf(repo, dir, dirFiles) ?: return@forEach
            val (group, artifact, version) = coordinate
            val acc = byCoordinate.getOrPut(group to artifact) { Accumulator() }
            acc.sizeBytes += dirFiles.sumOf { it.sizeBytes }
            val recency = dirFiles.mapNotNull { it.lastModified }.maxOrNull() ?: Instant.EPOCH
            acc.versions.merge(version, recency) { a, b -> maxOf(a, b) }
        }
        return byCoordinate.map { (ga, acc) ->
            CatalogEntry(
                repository = repo,
                group = ga.first,
                artifact = ga.second,
                latestVersion = acc.latest(),
                versionCount = acc.versions.size,
                sizeBytes = acc.sizeBytes,
            )
        }
    }

    /**
     * Treats [dir] as a coordinate version directory when its path is `{repo}/{group…}/{artifact}/{version}`
     * and it holds a primary artifact file named `{artifact}-…` with a recognized extension — the same
     * shape the browse API uses to detect a coordinate.
     */
    private fun coordinateOf(
        repo: String,
        dir: String,
        dirFiles: List<org.khorum.oss.relikquary.storage.StoredObject>,
    ): Triple<String, String, String>? {
        val relative = dir.removePrefix("$repo/")
        if (relative == dir || relative.isEmpty()) return null
        val segments = relative.split('/').filter { it.isNotEmpty() }
        if (segments.size < COORDINATE_MIN_SEGMENTS) return null
        val version = segments.last()
        val artifact = segments[segments.size - 2]
        val group = segments.subList(0, segments.size - 2).joinToString(".")
        if (group.isEmpty()) return null
        val isCoordinate = dirFiles.any { file ->
            val name = file.key.substringAfterLast('/')
            name.startsWith("$artifact-") && COORDINATE_EXTS.any { name.endsWith(it) }
        }
        return if (isCoordinate) Triple(group, artifact, version) else null
    }

    private class Accumulator {
        var sizeBytes: Long = 0
        val versions: MutableMap<String, Instant> = HashMap()

        /** Latest version by recency (matching hosted-metadata ordering), with the version string as tiebreak. */
        fun latest(): String =
            versions.entries.maxWithOrNull(compareBy({ it.value }, { it.key }))?.key ?: ""
    }

    private companion object {
        const val COORDINATE_MIN_SEGMENTS = 2
        val COORDINATE_EXTS = listOf(".pom", ".jar", ".module")
    }
}
