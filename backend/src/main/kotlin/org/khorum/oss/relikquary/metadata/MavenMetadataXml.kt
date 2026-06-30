package org.khorum.oss.relikquary.metadata

/**
 * Serializes [ArtifactMetadata]/[SnapshotMetadata] to byte-faithful Maven-layout `maven-metadata.xml`
 * bytes (feature 014). Output is deterministic (stable element order, UTF-8) so regeneration is
 * reproducible and the generated checksums always match the served bytes. JDK-only, no dependency.
 */
object MavenMetadataXml {

    private const val HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"

    fun toBytes(meta: ArtifactMetadata): ByteArray = buildString {
        append(HEADER)
        append("<metadata>\n")
        append("  <groupId>").append(esc(meta.groupId)).append("</groupId>\n")
        append("  <artifactId>").append(esc(meta.artifactId)).append("</artifactId>\n")
        append("  <versioning>\n")
        meta.latest?.let { append("    <latest>").append(esc(it)).append("</latest>\n") }
        meta.release?.let { append("    <release>").append(esc(it)).append("</release>\n") }
        append("    <versions>\n")
        meta.versions.forEach { append("      <version>").append(esc(it)).append("</version>\n") }
        append("    </versions>\n")
        append("    <lastUpdated>").append(esc(meta.lastUpdated)).append("</lastUpdated>\n")
        append("  </versioning>\n")
        append("</metadata>\n")
    }.toByteArray(Charsets.UTF_8)

    fun toBytes(meta: SnapshotMetadata): ByteArray = buildString {
        append(HEADER)
        append("<metadata>\n")
        append("  <groupId>").append(esc(meta.groupId)).append("</groupId>\n")
        append("  <artifactId>").append(esc(meta.artifactId)).append("</artifactId>\n")
        append("  <version>").append(esc(meta.version)).append("</version>\n")
        append("  <versioning>\n")
        append("    <snapshot>\n")
        append("      <timestamp>").append(esc(meta.timestamp)).append("</timestamp>\n")
        append("      <buildNumber>").append(meta.buildNumber).append("</buildNumber>\n")
        append("    </snapshot>\n")
        append("    <lastUpdated>").append(esc(meta.lastUpdated)).append("</lastUpdated>\n")
        append("    <snapshotVersions>\n")
        meta.snapshotVersions.forEach { appendSnapshotVersion(it) }
        append("    </snapshotVersions>\n")
        append("  </versioning>\n")
        append("</metadata>\n")
    }.toByteArray(Charsets.UTF_8)

    private fun StringBuilder.appendSnapshotVersion(sv: SnapshotVersion) {
        append("      <snapshotVersion>\n")
        sv.classifier?.let { append("        <classifier>").append(esc(it)).append("</classifier>\n") }
        append("        <extension>").append(esc(sv.extension)).append("</extension>\n")
        append("        <value>").append(esc(sv.value)).append("</value>\n")
        append("        <updated>").append(esc(sv.updated)).append("</updated>\n")
        append("      </snapshotVersion>\n")
    }

    private fun esc(text: String): String = buildString {
        text.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> append(c)
            }
        }
    }
}
