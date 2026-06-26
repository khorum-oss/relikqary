package org.khorum.oss.relikqary.protocol

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import org.khorum.oss.relikqary.coordinate.InvalidRepositoryPathException
import org.khorum.oss.relikqary.coordinate.RepositoryPath
import org.khorum.oss.relikqary.ingestion.RepublishPolicy
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
 * Serves the Maven-compatible repository protocol (contracts/repository-http.md): `PUT` publishes a
 * file, `GET`/`HEAD` resolve it. The request path maps 1:1 onto the storage key.
 */
@RestController
@RequestMapping
class RepositoryController(
    private val storage: ArtifactStorage,
    private val republishPolicy: RepublishPolicy,
) {

    @PutMapping("/**")
    fun publish(request: HttpServletRequest): ResponseEntity<Void> {
        val path = repositoryPath(request)
        val exists = storage.exists(path.key)
        if (!republishPolicy.isAllowed(path, exists)) {
            logger.info { "Rejecting re-publish of immutable release coordinate: ${path.key}" }
            return ResponseEntity.status(HttpStatus.CONFLICT).build()
        }
        val written = storage.write(path.key, request.inputStream)
        logger.info { "Stored ${path.key} ($written bytes)" }
        val status = if (exists) HttpStatus.OK else HttpStatus.CREATED
        return ResponseEntity.status(status).build()
    }

    @GetMapping("/**")
    fun resolve(request: HttpServletRequest): ResponseEntity<InputStreamResource> {
        val path = repositoryPath(request)
        val stored = storage.openRead(path.key)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(stored.sizeBytes)
            .body(InputStreamResource(stored.stream))
    }

    private fun repositoryPath(request: HttpServletRequest): RepositoryPath {
        val raw = request.requestURI.removePrefix(request.contextPath)
        val decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8)
        return RepositoryPath.of(decoded)
    }

    @ExceptionHandler(InvalidRepositoryPathException::class)
    fun handleInvalidPath(e: InvalidRepositoryPathException): ResponseEntity<String> {
        logger.debug { "Rejected invalid repository path: ${e.message}" }
        return ResponseEntity.badRequest().body(e.message)
    }
}
