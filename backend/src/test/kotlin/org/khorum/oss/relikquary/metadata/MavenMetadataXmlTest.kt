package org.khorum.oss.relikquary.metadata

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.xml.parsers.DocumentBuilderFactory

/** Unit tests for the metadata serializer (feature 014): shape, marker presence/omission, valid XML. */
class MavenMetadataXmlTest {

    @Test
    fun `artifact-level metadata lists versions with latest and release`() {
        val xml = MavenMetadataXml.toBytes(
            ArtifactMetadata(
                groupId = "com.example",
                artifactId = "widget",
                versions = listOf("1.0.0", "2.0.0"),
                latest = "2.0.0",
                release = "2.0.0",
                lastUpdated = "20260630120000",
            ),
        ).decodeToString()

        assertTrue(xml.contains("<groupId>com.example</groupId>"))
        assertTrue(xml.contains("<artifactId>widget</artifactId>"))
        assertTrue(xml.contains("<version>1.0.0</version>"))
        assertTrue(xml.contains("<version>2.0.0</version>"))
        assertTrue(xml.contains("<latest>2.0.0</latest>"))
        assertTrue(xml.contains("<release>2.0.0</release>"))
        assertTrue(xml.contains("<lastUpdated>20260630120000</lastUpdated>"))
        assertValidXml(xml)
    }

    @Test
    fun `snapshot-only artifact omits the release marker`() {
        val xml = MavenMetadataXml.toBytes(
            ArtifactMetadata(
                groupId = "com.example",
                artifactId = "widget",
                versions = listOf("3.0.0-SNAPSHOT"),
                latest = "3.0.0-SNAPSHOT",
                release = null,
                lastUpdated = "20260630120000",
            ),
        ).decodeToString()

        assertTrue(xml.contains("<latest>3.0.0-SNAPSHOT</latest>"))
        assertFalse(xml.contains("<release>")) { "snapshot-only artifact must omit <release>" }
        assertValidXml(xml)
    }

    @Test
    fun `version-level snapshot metadata carries the newest build and snapshotVersions`() {
        val xml = MavenMetadataXml.toBytes(
            SnapshotMetadata(
                groupId = "com.example",
                artifactId = "widget",
                version = "3.0.0-SNAPSHOT",
                timestamp = "20260630.120500",
                buildNumber = 2,
                lastUpdated = "20260630120500",
                snapshotVersions = listOf(
                    SnapshotVersion(null, "pom", "3.0.0-20260630.120500-2", "20260630120500"),
                    SnapshotVersion(null, "jar", "3.0.0-20260630.120500-2", "20260630120500"),
                    SnapshotVersion("sources", "jar", "3.0.0-20260630.120500-2", "20260630120500"),
                ),
            ),
        ).decodeToString()

        assertTrue(xml.contains("<timestamp>20260630.120500</timestamp>"))
        assertTrue(xml.contains("<buildNumber>2</buildNumber>"))
        assertTrue(xml.contains("<value>3.0.0-20260630.120500-2</value>"))
        assertTrue(xml.contains("<classifier>sources</classifier>"))
        assertValidXml(xml)
    }

    private fun assertValidXml(xml: String) {
        // Throws if not well-formed — proves byte-faithful, client-parseable output.
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(xml.byteInputStream())
    }
}
