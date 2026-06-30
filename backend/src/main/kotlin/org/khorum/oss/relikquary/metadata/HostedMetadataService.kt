package org.khorum.oss.relikquary.metadata

import org.khorum.oss.relikquary.config.RepositoryProperties
import org.khorum.oss.relikquary.coordinate.RepositoryPath
import org.khorum.oss.relikquary.storage.ArtifactStorage
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Makes Relikquary authoritative for hosted-repository `maven-metadata.xml` (feature 014). After a hosted
 * artifact is published, [regenerate] rebuilds the artifact-level listing (and, for snapshots, the
 * version-level snapshot metadata) from the versions actually in storage — independent of whatever a single
 * client uploaded. [ensureForRead] is the compute-on-read fallback when stored metadata is missing.
 *
 * Generation for one groupId:artifactId is serialized by an in-process per-coordinate lock so concurrent
 * publishes converge to the full version set; each metadata file plus its `.sha1`/`.md5` is written through
 * the storage's atomic [ArtifactStorage.write]. Only metadata/checksum keys are written — no other artifact
 * is touched. Proxy/group repositories are never passed here.
 */
@Service
class HostedMetadataService(private val storage: ArtifactStorage) {

    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    /** Rebuild metadata after publishing the hosted coordinate file at [path] in [repo]. */
    fun regenerate(repo: RepositoryProperties.Repo, path: RepositoryPath) {
        val storageKey = "${repo.name}/${path.key}"
        val artifactDir = storageKey.removeSuffix("/${path.version}/${path.fileName}")
        withCoordinateLock(artifactDir) {
            regenerateArtifact(artifactDir)
            if (path.version.endsWith(SNAPSHOT_SUFFIX)) regenerateSnapshot(artifactDir, path.version)
        }
    }

    /** Compute-on-read fallback: build the metadata for a hosted `maven-metadata.xml` [path] that is absent. */
    fun ensureForRead(repo: RepositoryProperties.Repo, path: RepositoryPath) {
        val dir = "${repo.name}/${path.key}".substringBeforeLast('/')
        val lastSegment = dir.substringAfterLast('/')
        val isVersionLevel = lastSegment.endsWith(SNAPSHOT_SUFFIX)
        val artifactDir = if (isVersionLevel) dir.substringBeforeLast('/') else dir
        withCoordinateLock(artifactDir) {
            regenerateArtifact(artifactDir)
            if (isVersionLevel) regenerateSnapshot(artifactDir, lastSegment)
        }
    }

    private fun regenerateArtifact(artifactDir: String) {
        val versions = storage.list(artifactDir).filter { it.isDirectory }.map { it.name }
        if (versions.isEmpty()) return
        val files = storage.walk(artifactDir)
        val recency = versions.associateWith { version ->
            files.filter { it.key.startsWith("$artifactDir/$version/") }
                .mapNotNull { it.lastModified }.maxOrNull() ?: Instant.EPOCH
        }
        val ordered = versions.sortedBy { recency.getValue(it) }
        val (groupId, artifactId) = groupAndArtifact(artifactDir)
        val meta = ArtifactMetadata(
            groupId = groupId,
            artifactId = artifactId,
            versions = ordered,
            latest = ordered.lastOrNull(),
            release = ordered.lastOrNull { !it.endsWith(SNAPSHOT_SUFFIX) },
            lastUpdated = stamp(recency.values.maxOrNull() ?: Instant.EPOCH),
        )
        writeWithChecksums("$artifactDir/$METADATA_FILE", MavenMetadataXml.toBytes(meta))
    }

    private fun regenerateSnapshot(artifactDir: String, version: String) {
        val (groupId, artifactId) = groupAndArtifact(artifactDir)
        val versionDir = "$artifactDir/$version"
        val builds = storage.walk(versionDir).mapNotNull { obj ->
            SnapshotBuild.parse(artifactId, obj.key.substringAfterLast('/'))
                ?.let { it to (obj.lastModified ?: Instant.EPOCH) }
        }
        if (builds.isEmpty()) return
        val newest = builds.maxWith(compareBy({ it.first.timestamp }, { it.first.buildNumber })).first
        val updated = builds.filter { it.first.isSameBuild(newest) }.maxOf { it.second }
        val base = version.removeSuffix(SNAPSHOT_SUFFIX)
        val value = "$base-${newest.timestamp}-${newest.buildNumber}"
        val entries = builds.map { it.first }.filter { it.isSameBuild(newest) }.distinct()
            .map { SnapshotVersion(it.classifier, it.extension, value, stamp(updated)) }
        val meta = SnapshotMetadata(
            groupId = groupId,
            artifactId = artifactId,
            version = version,
            timestamp = newest.timestamp,
            buildNumber = newest.buildNumber,
            lastUpdated = stamp(updated),
            snapshotVersions = entries,
        )
        writeWithChecksums("$versionDir/$METADATA_FILE", MavenMetadataXml.toBytes(meta))
    }

    private fun writeWithChecksums(key: String, bytes: ByteArray) {
        storage.write(key, bytes.inputStream())
        storage.write("$key.sha1", hex(bytes, "SHA-1").toByteArray(Charsets.US_ASCII).inputStream())
        storage.write("$key.md5", hex(bytes, "MD5").toByteArray(Charsets.US_ASCII).inputStream())
    }

    private fun groupAndArtifact(artifactDir: String): Pair<String, String> {
        val withoutRepo = artifactDir.substringAfter('/')
        val artifactId = withoutRepo.substringAfterLast('/')
        val groupId = withoutRepo.substringBeforeLast('/', "").replace('/', '.')
        return groupId to artifactId
    }

    private fun <T> withCoordinateLock(key: String, block: () -> T): T =
        locks.computeIfAbsent(key) { ReentrantLock() }.withLock(block)

    private fun BuildEntry.isSameBuild(other: BuildEntry): Boolean =
        timestamp == other.timestamp && buildNumber == other.buildNumber

    private companion object {
        const val METADATA_FILE = "maven-metadata.xml"
        const val SNAPSHOT_SUFFIX = "-SNAPSHOT"
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)

        fun stamp(instant: Instant): String = STAMP.format(instant)

        fun hex(bytes: ByteArray, algorithm: String): String =
            MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
