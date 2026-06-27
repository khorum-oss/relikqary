package org.khorum.oss.relikquary.coordinate

/** Thrown when a request path is not a safe, valid repository-layout path (FR-012). */
class InvalidRepositoryPathException(message: String) : RuntimeException(message)

/** Classification of a repository path, used by the re-publish policy. */
enum class PathKind { RELEASE, SNAPSHOT, METADATA }

/**
 * A validated, normalized repository-layout path used as a storage key.
 *
 * Construction guarantees the path cannot escape the repository root: no empty segments, no `.`/`..`
 * traversal segments, no absolute paths, no backslashes, and no control characters. Invalid input
 * throws [InvalidRepositoryPathException] (surfaced as HTTP 400).
 */
@JvmInline
value class RepositoryPath private constructor(val key: String) {

    val fileName: String get() = key.substringAfterLast('/')

    /** Directory segment immediately containing the file (the version dir for coordinate files). */
    private val parentSegment: String
        get() = key.substringBeforeLast('/', missingDelimiterValue = "").substringAfterLast('/')

    /**
     * Classify for re-publish decisions: artifact-level metadata is always overwritable, SNAPSHOT
     * versions are overwritable, and everything else is treated as a RELEASE coordinate file.
     */
    fun classify(): PathKind = when {
        fileName.startsWith(METADATA_FILE) -> PathKind.METADATA
        parentSegment.endsWith(SNAPSHOT_SUFFIX) -> PathKind.SNAPSHOT
        else -> PathKind.RELEASE
    }

    companion object {
        private const val METADATA_FILE = "maven-metadata.xml"
        private const val SNAPSHOT_SUFFIX = "-SNAPSHOT"

        fun of(rawPath: String): RepositoryPath {
            val trimmed = rawPath.trimStart('/')
            val reason = invalidReason(trimmed)
            if (reason != null) throw InvalidRepositoryPathException("$reason: $rawPath")
            return RepositoryPath(trimmed)
        }

        private fun invalidReason(trimmed: String): String? = when {
            trimmed.isEmpty() -> "empty repository path"
            trimmed.contains('\\') -> "backslash not allowed in path"
            else -> trimmed.split('/').firstNotNullOfOrNull { segmentReason(it) }
        }

        private fun segmentReason(segment: String): String? = when {
            segment.isEmpty() -> "empty path segment"
            segment == "." || segment == ".." -> "path traversal segment"
            segment.any { it.code < MIN_PRINTABLE } -> "control character in path"
            else -> null
        }
    }
}

private const val MIN_PRINTABLE = 0x20
