package org.khorum.oss.relikquary.unit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.khorum.oss.relikquary.config.RepositoryProperties
import org.khorum.oss.relikquary.config.RepositoryProperties.Repo
import org.khorum.oss.relikquary.repository.InvalidRepositoryConfigException
import org.khorum.oss.relikquary.repository.RepositoryKind
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

    private fun registryOf(vararg repos: Repo) = RepositoryRegistry(RepositoryProperties(repos.toList()))

    @Test
    fun `resolves a configured repository by name`() {
        assertEquals(RepositoryType.RELEASE, registry.require("releases").type)
        assertEquals(RepositoryType.SNAPSHOT, registry.require("snapshots").type)
    }

    @Test
    fun `throws for an unknown repository`() {
        assertThrows(RepositoryNotFoundException::class.java) { registry.require("nope") }
    }

    @Test
    fun `accepts a valid proxy and group configuration`() {
        val r = registryOf(
            Repo(name = "releases", type = RepositoryType.RELEASE),
            Repo(name = "central", kind = RepositoryKind.PROXY, remoteUrl = "https://repo1.maven.org/maven2"),
            Repo(name = "public", kind = RepositoryKind.GROUP, members = listOf("releases", "central")),
        )
        assertEquals(RepositoryKind.PROXY, r.require("central").kind)
        assertEquals(listOf("releases", "central"), r.require("public").members)
    }

    @Test
    fun `rejects a proxy without a remote url`() {
        assertThrows(InvalidRepositoryConfigException::class.java) {
            registryOf(Repo(name = "central", kind = RepositoryKind.PROXY))
        }
    }

    @Test
    fun `rejects a group with no members`() {
        assertThrows(InvalidRepositoryConfigException::class.java) {
            registryOf(Repo(name = "public", kind = RepositoryKind.GROUP))
        }
    }

    @Test
    fun `rejects a group referencing an unknown member`() {
        assertThrows(InvalidRepositoryConfigException::class.java) {
            registryOf(Repo(name = "public", kind = RepositoryKind.GROUP, members = listOf("ghost")))
        }
    }

    @Test
    fun `rejects a group referencing itself`() {
        assertThrows(InvalidRepositoryConfigException::class.java) {
            registryOf(Repo(name = "public", kind = RepositoryKind.GROUP, members = listOf("public")))
        }
    }

    @Test
    fun `rejects a nested group member`() {
        assertThrows(InvalidRepositoryConfigException::class.java) {
            registryOf(
                Repo(name = "releases", type = RepositoryType.RELEASE),
                Repo(name = "inner", kind = RepositoryKind.GROUP, members = listOf("releases")),
                Repo(name = "outer", kind = RepositoryKind.GROUP, members = listOf("inner")),
            )
        }
    }
}
