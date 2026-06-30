package org.khorum.oss.relikquary.metadata

/**
 * In-memory models of the two kinds of `maven-metadata.xml` Relikquary generates for hosted repositories
 * (feature 014). Both are serialized to byte-faithful Maven layout by [MavenMetadataXml].
 */

/**
 * Artifact-level metadata at `<groupPath>/<artifactId>/maven-metadata.xml`: the authoritative listing of
 * all stored versions with the latest/release markers and a last-updated stamp.
 */
data class ArtifactMetadata(
    val groupId: String,
    val artifactId: String,
    val versions: List<String>,
    /** Newest version overall; null when there are no versions. */
    val latest: String?,
    /** Newest non-snapshot (release) version; null when there is none. */
    val release: String?,
    /** UTC `yyyyMMddHHmmss`. */
    val lastUpdated: String,
)

/**
 * Version-level snapshot metadata at `<groupPath>/<artifactId>/<version>-SNAPSHOT/maven-metadata.xml`:
 * the newest build's timestamp/buildNumber and the per-(classifier, extension) snapshot version entries.
 */
data class SnapshotMetadata(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val timestamp: String,
    val buildNumber: Int,
    /** UTC `yyyyMMddHHmmss`. */
    val lastUpdated: String,
    val snapshotVersions: List<SnapshotVersion>,
)

/** One `<snapshotVersion>` entry: the resolved value for a (classifier, extension) of the newest build. */
data class SnapshotVersion(
    val classifier: String?,
    val extension: String,
    /** e.g. `2.0.0-20260101.120000-5`. */
    val value: String,
    /** UTC `yyyyMMddHHmmss`. */
    val updated: String,
)
