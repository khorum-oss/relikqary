package org.khorum.oss.relikquary.protocol

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import org.khorum.oss.relikquary.config.RepositoryProperties
import org.khorum.oss.relikquary.coordinate.InvalidRepositoryPathException
import org.khorum.oss.relikquary.coordinate.PathKind
import org.khorum.oss.relikquary.coordinate.RepositoryPath
import org.khorum.oss.relikquary.ingestion.PublishDecision
import org.khorum.oss.relikquary.ingestion.RepublishPolicy
import org.khorum.oss.relikquary.metadata.HostedMetadataService
import org.khorum.oss.relikquary.observability.metrics.RepositoryMetrics
import org.khorum.oss.relikquary.repository.RepositoryKind
import org.khorum.oss.relikquary.repository.RepositoryNotFoundException
import org.khorum.oss.relikquary.repository.RepositoryRegistry
import org.khorum.oss.relikquary.repository.RepositoryResolver
import org.khorum.oss.relikquary.repository.Resolution
import org.khorum.oss.relikquary.storage.ArtifactStorage
import org.springframework.core.io.InputStreamResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private val logger = KotlinLogging.logger {}

private const val METADATA_FILE = "maven-metadata.xml"

/**
 * Serves named repositories (feature 004, contracts/named-repositories.md). The first path segment is
 * the repository name; the remainder is the Maven-layout artifact path. Storage keys are namespaced as
 * `"{repo}/{artifactKey}"`.
 */
@RestController
@RequestMapping
class RepositoryController(
    private val storage: ArtifactStorage,
    private val republishPolicy: RepublishPolicy,
    private val registry: RepositoryRegistry,
    private val resolver: RepositoryResolver,
    private val metrics: RepositoryMetrics,
    private val metadataService: HostedMetadataService,
) {

    @PutMapping("/**")
    fun publish(request: HttpServletRequest): ResponseEntity<Void> {
        val target = target(request)
        if (target.repo.kind != RepositoryKind.HOSTED) {
            logger.info { "Rejecting publish to read-only ${target.repo.kind} repo '${target.repo.name}'" }
            metrics.recordPublish(target.repo.name, "rejected")
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .header(HttpHeaders.ALLOW, "GET", "HEAD").build()
        }
        // The server is authoritative for hosted maven-metadata.xml (feature 014): a client's uploaded
        // metadata (which reflects only its local view) is accepted but not stored — the server generates it.
        if (target.path.classify() == PathKind.METADATA) {
            return acceptHostedMetadata(target, request)
        }
        val exists = storage.exists(target.key)
        return when (republishPolicy.evaluate(target.repo.type, target.path, exists)) {
            PublishDecision.REJECT_TYPE -> {
                logger.info { "Rejecting ${target.path.key}: wrong coordinate kind for ${target.repo.type} repo '${target.repo.name}'" }
                metrics.recordPublish(target.repo.name, "rejected")
                ResponseEntity.badRequest().build()
            }
            PublishDecision.REJECT_IMMUTABLE -> {
                logger.info { "Rejecting re-publish of immutable release: ${target.key}" }
                metrics.recordPublish(target.repo.name, "rejected")
                ResponseEntity.status(HttpStatus.CONFLICT).build()
            }
            PublishDecision.ACCEPT -> {
                val written = storage.write(target.key, request.inputStream)
                logger.info { "Stored ${target.key} ($written bytes)" }
                // Rebuild the artifact-level (and snapshot) metadata from what is now stored, so an
                // independent publisher's later metadata upload never clobbers the true version set.
                metadataService.regenerate(target.repo, target.path)
                metrics.recordPublish(target.repo.name, "accepted")
                ResponseEntity.status(if (exists) HttpStatus.OK else HttpStatus.CREATED).build()
            }
        }
    }

    /**
     * Accepts a client's upload of a hosted `maven-metadata.xml` (or its checksum sibling) without storing
     * it — the server owns hosted metadata. The body is drained so the publish succeeds; on the metadata
     * file itself the authoritative copy is (re)generated. Coordinate publishes already keep it current.
     */
    private fun acceptHostedMetadata(target: Target, request: HttpServletRequest): ResponseEntity<Void> {
        request.inputStream.use { it.readBytes() }
        if (target.path.fileName == METADATA_FILE) {
            metadataService.ensureForRead(target.repo, target.path)
        }
        metrics.recordPublish(target.repo.name, "accepted")
        // The upload is accepted; the served metadata is the server's authoritative copy.
        return ResponseEntity.status(HttpStatus.CREATED).build()
    }

    @GetMapping("/**")
    fun resolve(request: HttpServletRequest): ResponseEntity<InputStreamResource> {
        val target = target(request)
        // Compute-on-read fallback (feature 014): if a hosted maven-metadata.xml (or its checksum) is not
        // present, build it authoritatively from stored versions before resolving. Hosted only — proxy
        // metadata stays pass-through.
        if (target.repo.kind == RepositoryKind.HOSTED &&
            target.path.fileName.startsWith(METADATA_FILE) &&
            !storage.exists(target.key)
        ) {
            metadataService.ensureForRead(target.repo, target.path)
        }
        return when (val resolution = resolver.resolve(target.repo.name, target.path)) {
            is Resolution.Hit -> {
                metrics.recordResolve(target.repo.name, "hit")
                val builder = ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                // A streamed proxy miss may have an unknown length (upstream omitted Content-Length);
                // omit the header so the response is chunked. Cache reads always know the size.
                resolution.artifact.sizeBytes?.let { builder.contentLength(it) }
                builder.body(InputStreamResource(resolution.artifact.stream))
            }
            Resolution.Miss -> {
                metrics.recordResolve(target.repo.name, "miss")
                ResponseEntity.notFound().build()
            }
            Resolution.UpstreamError -> {
                metrics.recordResolve(target.repo.name, "upstream_error")
                ResponseEntity.status(HttpStatus.BAD_GATEWAY).build()
            }
        }
    }

    private data class Target(val repo: RepositoryProperties.Repo, val path: RepositoryPath) {
        val key: String get() = "${repo.name}/${path.key}"
    }

    private fun target(request: HttpServletRequest): Target {
        val raw = request.requestURI.removePrefix(request.contextPath)
        val decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8).trimStart('/')
        val slash = decoded.indexOf('/')
        val repoName = if (slash < 0) decoded else decoded.substring(0, slash)
        val rest = if (slash < 0) "" else decoded.substring(slash + 1)
        val repo = registry.require(repoName)
        return Target(repo, RepositoryPath.of(rest))
    }

    @ExceptionHandler(InvalidRepositoryPathException::class)
    fun handleInvalidPath(e: InvalidRepositoryPathException): ResponseEntity<String> {
        logger.debug { "Rejected invalid repository path: ${e.message}" }
        return ResponseEntity.badRequest().body(e.message)
    }

    @ExceptionHandler(RepositoryNotFoundException::class)
    fun handleUnknownRepository(e: RepositoryNotFoundException): ResponseEntity<String> {
        logger.debug { "Rejected unknown repository: ${e.message}" }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.message)
    }
}
