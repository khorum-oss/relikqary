package org.khorum.oss.relikquary.unit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.khorum.oss.relikquary.coordinate.PathKind
import org.khorum.oss.relikquary.coordinate.RepositoryPath

/**
 * Coordinate-matching recognition of Gradle Module Metadata (feature 011, FR-002), and a guard that the
 * existing `classify()` (which drives release/snapshot immutability) is unchanged for `.module` files.
 */
class RepositoryPathModuleTest {

    @Test
    fun `recognizes a release module by coordinate-matching filename`() {
        val path = RepositoryPath.of("com/acme/widget/1.2.3/widget-1.2.3.module")
        assertTrue(path.isModuleMetadata())
        assertEquals("widget", path.artifactId)
        assertEquals("1.2.3", path.version)
    }

    @Test
    fun `recognizes a timestamped snapshot module`() {
        val path = RepositoryPath.of("com/acme/widget/1.0-SNAPSHOT/widget-1.0-20260101.120000-1.module")
        assertTrue(path.isModuleMetadata())
        assertEquals("widget", path.artifactId)
    }

    @Test
    fun `rejects a module file that does not match the coordinate`() {
        assertFalse(RepositoryPath.of("com/acme/widget/1.2.3/other-1.0.module").isModuleMetadata())
    }

    @Test
    fun `rejects non-module files`() {
        assertFalse(RepositoryPath.of("com/acme/widget/1.2.3/widget-1.2.3.jar").isModuleMetadata())
        assertFalse(RepositoryPath.of("com/acme/widget/1.2.3/widget-1.2.3.pom").isModuleMetadata())
        assertFalse(RepositoryPath.of("com/acme/widget/maven-metadata.xml").isModuleMetadata())
    }

    @Test
    fun `classify is unchanged for module files (immutability inputs preserved)`() {
        assertEquals(PathKind.RELEASE, RepositoryPath.of("com/acme/widget/1.2.3/widget-1.2.3.module").classify())
        assertEquals(
            PathKind.SNAPSHOT,
            RepositoryPath.of("com/acme/widget/1.0-SNAPSHOT/widget-1.0-20260101.120000-1.module").classify(),
        )
    }
}
