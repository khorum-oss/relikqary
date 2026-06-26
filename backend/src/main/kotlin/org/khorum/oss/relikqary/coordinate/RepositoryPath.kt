package org.khorum.oss.relikqary.coordinate

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
            if (trimmed.isEmpty()) throw InvalidRepositoryPathException("empty repository path")
            if (trimmed.contains('\\')) {
                throw InvalidRepositoryPathException("backslash not allowed in path: $rawPath")
            }
            val segments = trimmed.split('/')
            for (segment in segments) {
                when {
                    segment.isEmpty() ->
                        throw InvalidRepositoryPathException("empty path segment in: $rawPath")
                    segment == "." || segment == ".." ->
                        throw InvalidRepositoryPathException("path traversal segment in: $rawPath")
                    segment.any { it.code < MIN_PRINTABLE } ->
                        throw InvalidRepositoryPathException("control character in path: $rawPath")
                }
            }
            return RepositoryPath(segments.joinToString("/"))
        }
    }
}

private const val MIN_PRINTABLE = 0x20
