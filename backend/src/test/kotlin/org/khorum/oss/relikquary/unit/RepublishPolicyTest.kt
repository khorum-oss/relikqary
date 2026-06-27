package org.khorum.oss.relikquary.unit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.khorum.oss.relikquary.config.PublishProperties
import org.khorum.oss.relikquary.config.PublishProperties.ReleasePolicy
import org.khorum.oss.relikquary.coordinate.RepositoryPath
import org.khorum.oss.relikquary.ingestion.PublishDecision
import org.khorum.oss.relikquary.ingestion.RepublishPolicy
import org.khorum.oss.relikquary.repository.RepositoryType

class RepublishPolicyTest {

    private val release = RepositoryPath.of("com/example/widget/1.0.0/widget-1.0.0.jar")
    private val snapshot = RepositoryPath.of("com/example/widget/1.1.0-SNAPSHOT/widget-1.1.0-SNAPSHOT.jar")
    private val metadata = RepositoryPath.of("com/example/widget/maven-metadata.xml")

    private fun policy(p: ReleasePolicy = ReleasePolicy.REJECT) = RepublishPolicy(PublishProperties(releasePolicy = p))

    // --- RELEASE repo ---

    @Test
    fun `release repo accepts a new release`() {
        assertEquals(PublishDecision.ACCEPT, policy().evaluate(RepositoryType.RELEASE, release, alreadyExists = false))
    }

    @Test
    fun `release repo rejects an existing release as immutable`() {
        assertEquals(PublishDecision.REJECT_IMMUTABLE, policy().evaluate(RepositoryType.RELEASE, release, alreadyExists = true))
    }

    @Test
    fun `release repo allows overwrite when configured`() {
        val decision = policy(ReleasePolicy.OVERWRITE).evaluate(RepositoryType.RELEASE, release, alreadyExists = true)
        assertEquals(PublishDecision.ACCEPT, decision)
    }

    @Test
    fun `release repo rejects a snapshot coordinate as type mismatch`() {
        assertEquals(PublishDecision.REJECT_TYPE, policy().evaluate(RepositoryType.RELEASE, snapshot, alreadyExists = false))
    }

    @Test
    fun `release repo always allows metadata`() {
        assertEquals(PublishDecision.ACCEPT, policy().evaluate(RepositoryType.RELEASE, metadata, alreadyExists = true))
    }

    // --- SNAPSHOT repo ---

    @Test
    fun `snapshot repo accepts and overwrites snapshots`() {
        assertEquals(PublishDecision.ACCEPT, policy().evaluate(RepositoryType.SNAPSHOT, snapshot, alreadyExists = false))
        assertEquals(PublishDecision.ACCEPT, policy().evaluate(RepositoryType.SNAPSHOT, snapshot, alreadyExists = true))
    }

    @Test
    fun `snapshot repo rejects a release coordinate as type mismatch`() {
        assertEquals(PublishDecision.REJECT_TYPE, policy().evaluate(RepositoryType.SNAPSHOT, release, alreadyExists = false))
    }

    // --- MIXED repo ---

    @Test
    fun `mixed repo follows the version-string rule`() {
        assertEquals(PublishDecision.ACCEPT, policy().evaluate(RepositoryType.MIXED, snapshot, alreadyExists = true))
        assertEquals(PublishDecision.REJECT_IMMUTABLE, policy().evaluate(RepositoryType.MIXED, release, alreadyExists = true))
        assertEquals(PublishDecision.ACCEPT, policy().evaluate(RepositoryType.MIXED, release, alreadyExists = false))
    }
}
