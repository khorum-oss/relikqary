package org.khorum.oss.relikquary.repository

import io.github.oshai.kotlinlogging.KotlinLogging
import org.khorum.oss.relikquary.config.RepositoryProperties
import org.khorum.oss.relikquary.coordinate.PathKind
import org.khorum.oss.relikquary.coordinate.RepositoryPath
import org.khorum.oss.relikquary.proxy.UpstreamClient
import org.khorum.oss.relikquary.proxy.UpstreamResponse
import org.khorum.oss.relikquary.security.Action
import org.khorum.oss.relikquary.security.RepositoryAuthorizer
import org.khorum.oss.relikquary.storage.ArtifactStorage
import org.khorum.oss.relikquary.storage.StoredArtifact
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream

private val logger = KotlinLogging.logger {}

/** The outcome of resolving a read request against a repository (feature 006). */
sealed interface Resolution {
    /** The artifact was found (locally, cached, or fetched). The caller closes the stream. */
    data class Hit(val artifact: StoredArtifact) : Resolution

    /** The artifact is not present here (and, for a proxy, not upstream) ⇒ HTTP 404. */
    data object Miss : Resolution

    /** A proxy upstream was unreachable or errored ⇒ HTTP 502. */
    data object UpstreamError : Resolution
}

/**
 * Resolves a GET against a repository by dispatching on its [RepositoryKind] (feature 006):
 * HOSTED reads local storage; PROXY serves the local cache or fetches+caches from the upstream
 * (metadata is always pass-through, never cached); GROUP tries each member in order, first match wins.
 */
@Component
class RepositoryResolver(
    private val registry: RepositoryRegistry,
    private val storage: ArtifactStorage,
    private val upstream: UpstreamClient,
    private val authorizer: RepositoryAuthorizer,
) {

    fun resolve(repoName: String, path: RepositoryPath): Resolution {
        val repo = registry.require(repoName)
        return when (repo.kind) {
            RepositoryKind.HOSTED -> hosted(repo, path)
            RepositoryKind.PROXY -> proxy(repo, path)
            RepositoryKind.GROUP -> group(repo, path)
        }
    }

    private fun hosted(repo: RepositoryProperties.Repo, path: RepositoryPath): Resolution =
        storage.openRead("${repo.name}/${path.key}")?.let { Resolution.Hit(it) } ?: Resolution.Miss

    private fun proxy(repo: RepositoryProperties.Repo, path: RepositoryPath): Resolution {
        if (path.classify() == PathKind.METADATA) return passThrough(repo, path)
        val cacheKey = "${repo.name}/${path.key}"
        storage.openRead(cacheKey)?.let { return Resolution.Hit(it) }
        return when (val response = upstream.fetch(repo, path.key)) {
            is UpstreamResponse.Found -> {
                storage.write(cacheKey, response.stream)
                logger.info { "Cached ${repo.name}/${path.key} from upstream" }
                storage.openRead(cacheKey)?.let { Resolution.Hit(it) } ?: Resolution.Miss
            }
            UpstreamResponse.NotFound -> Resolution.Miss
            UpstreamResponse.Error -> Resolution.UpstreamError
        }
    }

    /** maven-metadata.xml: always served fresh from the upstream, never cached (FR-005). */
    private fun passThrough(repo: RepositoryProperties.Repo, path: RepositoryPath): Resolution =
        when (val response = upstream.fetch(repo, path.key)) {
            is UpstreamResponse.Found -> Resolution.Hit(materialize(response))
            UpstreamResponse.NotFound -> Resolution.Miss
            UpstreamResponse.Error -> Resolution.UpstreamError
        }

    private fun materialize(found: UpstreamResponse.Found): StoredArtifact {
        if (found.contentLength != null) return StoredArtifact(found.stream, found.contentLength)
        val bytes = found.stream.use { it.readBytes() }
        return StoredArtifact(ByteArrayInputStream(bytes), bytes.size.toLong())
    }

    /**
     * Resolves a group by first match, applying each member's READ policy (feature 007): a member that
     * denies the requesting user is skipped like a non-serving member (permissive union), so a private
     * member never masks a public copy and group reads never emit a 401 challenge.
     */
    private fun group(repo: RepositoryProperties.Repo, path: RepositoryPath): Resolution {
        val authentication = SecurityContextHolder.getContext().authentication
        var sawError = false
        for (member in repo.members) {
            if (!authorizer.permits(registry.require(member), Action.READ, authentication)) continue
            when (val result = resolve(member, path)) {
                is Resolution.Hit -> return result
                Resolution.Miss -> Unit
                Resolution.UpstreamError -> sawError = true
            }
        }
        return if (sawError) Resolution.UpstreamError else Resolution.Miss
    }
}
