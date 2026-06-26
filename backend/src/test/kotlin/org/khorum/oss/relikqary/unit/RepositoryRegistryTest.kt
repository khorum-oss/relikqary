package org.khorum.oss.relikqary.unit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.khorum.oss.relikqary.config.RepositoryProperties
import org.khorum.oss.relikqary.config.RepositoryProperties.Repo
import org.khorum.oss.relikqary.repository.RepositoryNotFoundException
import org.khorum.oss.relikqary.repository.RepositoryRegistry
import org.khorum.oss.relikqary.repository.RepositoryType

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
