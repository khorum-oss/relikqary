package org.khorum.oss.relikquary.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.khorum.oss.relikquary.repository.RepositoryKind
import org.khorum.oss.relikquary.repository.RepositoryRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Feature 012: the shipped default configuration makes the Gradle Plugin Portal a first-class proxied
 * upstream out of the box. Offline-safe — asserts only the loaded configuration, no network.
 */
@SpringBootTest(properties = ["relikquary.security.enabled=false"])
class DefaultRepositoriesTest {

    @Autowired
    lateinit var registry: RepositoryRegistry

    @Test
    fun `default config ships a gradle-plugins proxy pointed at the plugin portal`() {
        val pluginPortal = registry.require("gradle-plugins")
        assertEquals(RepositoryKind.PROXY, pluginPortal.kind)
        assertEquals("https://plugins.gradle.org/m2/", pluginPortal.remoteUrl)
    }

    @Test
    fun `default public group includes gradle-plugins last`() {
        val public = registry.require("public")
        assertEquals(RepositoryKind.GROUP, public.kind)
        // Order matters (first match wins): local releases, then Central, then the portal last.
        assertEquals(listOf("releases", "maven-central", "gradle-plugins"), public.members)
    }
}

/**
 * Feature 012 (FR-007/SC-004): the plugin-portal upstream URL is overridable via
 * `RELIKQUARY_GRADLE_PLUGIN_PORTAL_URL` with no code change. Here the override is supplied; the
 * `gradle-plugins` proxy's effective `remoteUrl` must reflect it. Offline-safe.
 */
@SpringBootTest(properties = ["relikquary.security.enabled=false"])
class GradlePluginPortalUrlOverrideTest {

    @Autowired
    lateinit var registry: RepositoryRegistry

    companion object {
        private const val OVERRIDE = "https://mirror.example.test/m2/"

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("RELIKQUARY_GRADLE_PLUGIN_PORTAL_URL") { OVERRIDE }
        }
    }

    @Test
    fun `the plugin-portal upstream URL honours the environment override`() {
        assertEquals(OVERRIDE, registry.require("gradle-plugins").remoteUrl)
    }
}
