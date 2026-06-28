package org.khorum.oss.relikquary.cleanup

import io.github.oshai.kotlinlogging.KotlinLogging
import org.khorum.oss.relikquary.config.RepositoryProperties
import org.khorum.oss.relikquary.config.RepositoryProperties.CacheEviction
import org.khorum.oss.relikquary.config.RepositoryProperties.SnapshotRetention
import org.khorum.oss.relikquary.protocol.dto.CleanupReport
import org.khorum.oss.relikquary.protocol.dto.RepoCleanupResult
import org.khorum.oss.relikquary.repository.RepositoryKind
import org.khorum.oss.relikquary.repository.RepositoryRegistry
import org.khorum.oss.relikquary.repository.RepositoryType
import org.khorum.oss.relikquary.storage.ArtifactStorage
import org.khorum.oss.relikquary.storage.StoredObject
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

/**
 * Runs retention/cleanup (feature 009): snapshot retention for hosted snapshot/mixed repos and cache
 * eviction for proxy repos. Selection is pure and side-effect free (unit-testable); deletion is the
 * single mutation point, skipped on a dry-run. Release repositories, `maven-metadata.xml`, and the
 * newest snapshot build are never selected.
 */
@Component
class CleanupService(
    private val registry: RepositoryRegistry,
    private val storage: ArtifactStorage,
) {

    fun run(dryRun: Boolean): CleanupReport {
        val now = Instant.now()
        val results = registry.all().mapNotNull { repo -> cleanRepo(repo, now, dryRun) }
        return CleanupReport(
            dryRun = dryRun,
            itemsRemoved = results.sumOf { it.itemsRemoved },
            bytesReclaimed = results.sumOf { it.bytesReclaimed },
            repositories = results,
        )
    }

    private fun cleanRepo(repo: RepositoryProperties.Repo, now: Instant, dryRun: Boolean): RepoCleanupResult? {
        val policy = repo.retention ?: return null
        val files = lazy { storage.walk("${repo.name}/") }
        val selected = when {
            repo.kind == RepositoryKind.HOSTED && repo.type != RepositoryType.RELEASE && policy.snapshot != null ->
                selectSnapshotDeletions(files.value, policy.snapshot, now)
            repo.kind == RepositoryKind.PROXY && policy.cache != null ->
                selectCacheEvictions(files.value, policy.cache, now)
            else -> emptyList()
        }
        if (!dryRun) {
            selected.forEach { storage.delete(it.key) }
            if (selected.isNotEmpty()) {
                logger.info { "Cleanup removed ${selected.size} files from '${repo.name}'" }
            }
        }
        return RepoCleanupResult(repo.name, selected.size, selected.sumOf { it.sizeBytes })
    }

    companion object {
        private val BUILD_TOKEN = Regex("""(\d{8}\.\d{6})-(\d+)""")
        private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss")

        /**
         * Selects the snapshot-build files to delete: within each `-SNAPSHOT` version directory, files are
         * grouped by Maven timestamp/build-number into builds; builds beyond [SnapshotRetention.keepLast]
         * and/or older than [SnapshotRetention.maxAge] are selected, but the newest build of each artifact
         * is always kept and `maven-metadata.xml`/non-timestamped files are never selected.
         */
        fun selectSnapshotDeletions(
            files: List<StoredObject>,
            policy: SnapshotRetention,
            now: Instant,
        ): List<StoredObject> {
            if (policy.keepLast == null && policy.maxAge == null) return emptyList()
            val cutoff = policy.maxAge?.let { now.minus(it) }
            val keep = (policy.keepLast ?: 1).coerceAtLeast(1) // always protect at least the newest build

            // version dir -> (buildId -> files); only files in a -SNAPSHOT dir carrying a build token.
            return files
                .mapNotNull { obj -> buildOf(obj)?.let { (versionDir, buildId, ts) -> Build(versionDir, buildId, ts, obj) } }
                .groupBy { it.versionDir }
                .values
                .flatMap { artifactBuilds -> snapshotDeletions(artifactBuilds, keep, policy.keepLast != null, cutoff) }
        }

        private fun snapshotDeletions(
            builds: List<Build>,
            keep: Int,
            countRuleActive: Boolean,
            cutoff: Instant?,
        ): List<StoredObject> {
            // Newest build first; group files by build id within this artifact.
            val byBuild = builds.groupBy { it.buildId }
            val ordered = byBuild.entries.sortedByDescending { it.value.first().timestamp }
            val toDelete = mutableListOf<StoredObject>()
            ordered.forEachIndexed { index, entry ->
                if (index < keep) return@forEachIndexed // protected (newest / keep-last)
                val tooOld = cutoff?.let { entry.value.first().timestamp.isBefore(it) } == true
                if (countRuleActive || tooOld) {
                    toDelete += entry.value.map { it.file }
                }
            }
            return toDelete
        }

        private fun buildOf(obj: StoredObject): Triple<String, String, Instant>? {
            val versionDir = obj.key.substringBeforeLast('/', "")
            if (!versionDir.substringAfterLast('/').endsWith("-SNAPSHOT")) return null
            val fileName = obj.key.substringAfterLast('/')
            if (fileName.startsWith("maven-metadata.xml")) return null
            val match = BUILD_TOKEN.find(fileName) ?: return null
            val ts = LocalDateTime.parse(match.groupValues[1], TIMESTAMP_FORMAT).toInstant(ZoneOffset.UTC)
            return Triple(versionDir, match.value, ts)
        }

        /**
         * Selects proxy cache files to evict: those older than [CacheEviction.maxAge], then (for
         * [CacheEviction.maxSize]) the oldest remaining files until the cache total is within budget.
         */
        fun selectCacheEvictions(
            files: List<StoredObject>,
            policy: CacheEviction,
            now: Instant,
        ): List<StoredObject> {
            val cacheable = files.filterNot { it.key.substringAfterLast('/').startsWith("maven-metadata.xml") }
            val selected = LinkedHashSet<StoredObject>()
            policy.maxAge?.let { age ->
                val cutoff = now.minus(age)
                cacheable.filter { it.lastModified?.isBefore(cutoff) == true }.forEach { selected += it }
            }
            policy.maxSize?.let { budget ->
                val remaining = cacheable.filterNot { it in selected }.sortedBy { it.lastModified ?: Instant.EPOCH }
                var total = remaining.sumOf { it.sizeBytes }
                for (obj in remaining) {
                    if (total <= budget.toBytes()) break
                    selected += obj
                    total -= obj.sizeBytes
                }
            }
            return selected.toList()
        }

        private data class Build(val versionDir: String, val buildId: String, val timestamp: Instant, val file: StoredObject)
    }
}
