package org.khorum.oss.relikqary.protocol

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import org.khorum.oss.relikqary.config.RepositoryProperties
import org.khorum.oss.relikqary.coordinate.InvalidRepositoryPathException
import org.khorum.oss.relikqary.coordinate.RepositoryPath
import org.khorum.oss.relikqary.ingestion.PublishDecision
import org.khorum.oss.relikqary.ingestion.RepublishPolicy
import org.khorum.oss.relikqary.repository.RepositoryNotFoundException
import org.khorum.oss.relikqary.repository.RepositoryRegistry
import org.khorum.oss.relikqary.storage.ArtifactStorage
import org.springframework.core.io.InputStreamResource
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
) {

    @PutMapping("/**")
    fun publish(request: HttpServletRequest): ResponseEntity<Void> {
        val target = target(request)
        val exists = storage.exists(target.key)
        return when (republishPolicy.evaluate(target.repo.type, target.path, exists)) {
            PublishDecision.REJECT_TYPE -> {
                logger.info { "Rejecting ${target.path.key}: wrong coordinate kind for ${target.repo.type} repo '${target.repo.name}'" }
                ResponseEntity.badRequest().build()
            }
            PublishDecision.REJECT_IMMUTABLE -> {
                logger.info { "Rejecting re-publish of immutable release: ${target.key}" }
                ResponseEntity.status(HttpStatus.CONFLICT).build()
            }
            PublishDecision.ACCEPT -> {
                val written = storage.write(target.key, request.inputStream)
                logger.info { "Stored ${target.key} ($written bytes)" }
                ResponseEntity.status(if (exists) HttpStatus.OK else HttpStatus.CREATED).build()
            }
        }
    }

    @GetMapping("/**")
    fun resolve(request: HttpServletRequest): ResponseEntity<InputStreamResource> {
        val target = target(request)
        val stored = storage.openRead(target.key)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(stored.sizeBytes)
            .body(InputStreamResource(stored.stream))
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
