package org.khorum.oss.relikquary.unit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.khorum.oss.relikquary.coordinate.InvalidRepositoryPathException
import org.khorum.oss.relikquary.coordinate.PathKind
import org.khorum.oss.relikquary.coordinate.RepositoryPath

class RepositoryPathTest {

    @Test
    fun `normalizes a leading slash into a clean storage key`() {
        assertEquals("com/example/widget/1.0.0/widget-1.0.0.jar", RepositoryPath.of("/com/example/widget/1.0.0/widget-1.0.0.jar").key)
    }

    @Test
    fun `classifies a release coordinate file`() {
        assertEquals(PathKind.RELEASE, RepositoryPath.of("com/example/widget/1.0.0/widget-1.0.0.jar").classify())
    }

    @Test
    fun `classifies a snapshot coordinate file`() {
        assertEquals(PathKind.SNAPSHOT, RepositoryPath.of("com/example/widget/1.1.0-SNAPSHOT/widget-1.1.0-SNAPSHOT.jar").classify())
    }

    @Test
    fun `classifies artifact-level maven-metadata as metadata`() {
        assertEquals(PathKind.METADATA, RepositoryPath.of("com/example/widget/maven-metadata.xml").classify())
        assertEquals(PathKind.METADATA, RepositoryPath.of("com/example/widget/maven-metadata.xml.sha1").classify())
    }

    @Test
    fun `rejects parent-traversal segments`() {
        assertThrows(InvalidRepositoryPathException::class.java) { RepositoryPath.of("com/example/../../etc/passwd") }
    }

    @Test
    fun `rejects current-dir segments`() {
        assertThrows(InvalidRepositoryPathException::class.java) { RepositoryPath.of("com/./example/widget") }
    }

    @Test
    fun `rejects empty segments`() {
        assertThrows(InvalidRepositoryPathException::class.java) { RepositoryPath.of("com//example/widget") }
    }

    @Test
    fun `rejects backslashes`() {
        assertThrows(InvalidRepositoryPathException::class.java) { RepositoryPath.of("com\\example\\widget") }
    }

    @Test
    fun `rejects an empty path`() {
        assertThrows(InvalidRepositoryPathException::class.java) { RepositoryPath.of("/") }
    }
}
