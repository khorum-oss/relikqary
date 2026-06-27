package org.khorum.oss.relikquary.unit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.khorum.oss.relikquary.config.RepositoryProperties
import org.khorum.oss.relikquary.config.RepositoryProperties.Repo
import org.khorum.oss.relikquary.repository.RepositoryNotFoundException
import org.khorum.oss.relikquary.repository.RepositoryRegistry
import org.khorum.oss.relikquary.repository.RepositoryType

class RepositoryRegistryTest {

    private val registry = RepositoryRegistry(
        RepositoryProperties(
            listOf(
                Repo(name = "releases", type = RepositoryType.RELEASE),
                Repo(name = "snapshots", type = RepositoryType.SNAPSHOT),
            ),
        ),
    )

    @Test
    fun `resolves a configured repository by name`() {
        assertEquals(RepositoryType.RELEASE, registry.require("releases").type)
        assertEquals(RepositoryType.SNAPSHOT, registry.require("snapshots").type)
    }

    @Test
    fun `throws for an unknown repository`() {
        assertThrows(RepositoryNotFoundException::class.java) { registry.require("nope") }
    }
}
