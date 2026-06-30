package org.khorum.oss.relikquary.metadata

import java.time.format.DateTimeFormatter

/**
 * The canonical Maven snapshot build token (`YYYYMMDD.HHMMSS-buildNumber`, e.g. `20260101.120000-5`) and
 * the parsing of timestamped snapshot filenames. This is the single definition of the snapshot format,
 * shared by retention/cleanup (feature 009) and hosted metadata generation (feature 014).
 */
object SnapshotBuild {

    /** Matches a Maven snapshot timestamp + build number: group 1 = `yyyyMMdd.HHmmss`, group 2 = build number. */
    val TOKEN: Regex = Regex("""(\d{8}\.\d{6})-(\d+)""")

    /** Formatter for the snapshot timestamp component (`yyyyMMdd.HHmmss`). */
    val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss")

    private val CHECKSUM_OR_SIGNATURE = setOf("sha1", "md5", "sha256", "sha512", "asc")

    /**
     * Parses a timestamped snapshot artifact filename of the form
     * `{artifactId}-{base}-{timestamp}-{buildNumber}[-{classifier}].{extension}`. Returns null for files
     * that are not this artifact's timestamped builds, and for checksum/signature siblings (`.sha1` etc.).
     */
    fun parse(artifactId: String, fileName: String): BuildEntry? {
        if (!fileName.startsWith("$artifactId-")) return null
        val match = TOKEN.find(fileName) ?: return null
        val afterToken = fileName.substring(match.range.last + 1)
        val extension = afterToken.substringAfterLast('.', "")
        if (extension.isEmpty() || extension in CHECKSUM_OR_SIGNATURE) return null
        val classifier = if (afterToken.startsWith("-")) {
            afterToken.substring(1).substringBefore('.').ifEmpty { null }
        } else {
            null
        }
        return BuildEntry(
            timestamp = match.groupValues[1],
            buildNumber = match.groupValues[2].toInt(),
            classifier = classifier,
            extension = extension,
        )
    }
}

/** One timestamped snapshot artifact: its build identity (timestamp + buildNumber) and its (classifier, extension). */
data class BuildEntry(
    val timestamp: String,
    val buildNumber: Int,
    val classifier: String?,
    val extension: String,
)
