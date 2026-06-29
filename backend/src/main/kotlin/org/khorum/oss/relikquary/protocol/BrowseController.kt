package org.khorum.oss.relikquary.protocol

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import org.khorum.oss.relikquary.coordinate.InvalidRepositoryPathException
import org.khorum.oss.relikquary.coordinate.RepositoryPath
import org.khorum.oss.relikquary.gradle.GradleModuleMetadataParser
import org.khorum.oss.relikquary.gradle.ParseResult
import org.khorum.oss.relikquary.protocol.dto.ContentsResponse
import org.khorum.oss.relikquary.protocol.dto.Coordinate
import org.khorum.oss.relikquary.protocol.dto.FileDetails
import org.khorum.oss.relikquary.protocol.dto.ListingEntry
import org.khorum.oss.relikquary.protocol.dto.ModuleMetadataResponse
import org.khorum.oss.relikquary.protocol.dto.ModuleRef
import org.khorum.oss.relikquary.protocol.dto.RepositorySummary
import org.khorum.oss.relikquary.repository.RepositoryNotFoundException
import org.khorum.oss.relikquary.repository.RepositoryRegistry
import org.khorum.oss.relikquary.storage.ArtifactStorage
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private val logger = KotlinLogging.logger {}

/**
 * JSON browse/manage API (feature 005, contracts/browse-api.md), separate from the Maven protocol.
 * Lists repositories and their contents, returns file details, and deletes files/prefixes. Downloads
 * reuse the Maven GET on the repository path.
 */
@RestController
@RequestMapping("/api")
class BrowseController(
    private val registry: RepositoryRegistry,
    private val storage: ArtifactStorage,
    private val moduleParser: GradleModuleMetadataParser,
) {

    @GetMapping("/repositories")
    fun repositories(): List<RepositorySummary> =
        registry.all().map { RepositorySummary(it.name, it.type.name, it.kind.name) }

    @GetMapping("/repositories/{repo}/contents", "/repositories/{repo}/contents/**")
    fun contents(@PathVariable repo: String, request: HttpServletRequest): ContentsResponse {
        registry.require(repo)
        val path = trailing(request, "/api/repositories/$repo/contents")
        val prefix = if (path.isEmpty()) repo else "$repo/${RepositoryPath.of(path).key}"
        val entries = storage.list(prefix).map {
            ListingEntry(it.name, if (it.isDirectory) "folder" else "file", it.sizeBytes, it.lastModified)
        }
        val (coordinate, module) = coordinateAndModule(path, entries)
        return ContentsResponse(repo, path, entries, coordinate, module)
    }

    /**
     * Derives the coordinate when [path] is a version directory (≥2 segments) whose files are named for
     * the coordinate (`{artifact}-{version}.{pom|jar|module}`), plus a reference to its recognized
     * `.module` if present. Both null for non-coordinate directories.
     */
    private fun coordinateAndModule(path: String, entries: List<ListingEntry>): Pair<Coordinate?, ModuleRef?> {
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.size < COORDINATE_MIN_SEGMENTS) return null to null
        val artifact = segments[segments.size - 2]
        val version = segments[segments.size - 1]
        val group = segments.subList(0, segments.size - 2).joinToString(".")
        val isCoordinate = group.isNotEmpty() && entries.any { entry ->
            entry.kind == "file" && entry.name.startsWith("$artifact-") && COORDINATE_EXTS.any { entry.name.endsWith(it) }
        }
        if (!isCoordinate) return null to null
        val moduleEntry = entries.firstOrNull { entry ->
            entry.kind == "file" && RepositoryPath.of("$path/${entry.name}").isModuleMetadata()
        }
        return Coordinate(group, artifact, version) to moduleEntry?.let { ModuleRef("$path/${it.name}") }
    }

    @GetMapping("/repositories/{repo}/module/**")
    fun module(@PathVariable repo: String, request: HttpServletRequest): ModuleMetadataResponse {
        registry.require(repo)
        val artifact = RepositoryPath.of(trailing(request, "/api/repositories/$repo/module"))
        if (!artifact.isModuleMetadata()) throw ResponseStatusException(HttpStatus.NOT_FOUND, "not a module")
        val bytes = storage.openRead("$repo/${artifact.key}")?.stream?.use { it.readBytes() }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no such module")
        return when (val result = moduleParser.parse(bytes)) {
            is ParseResult.Parsed -> ModuleMetadataResponse(
                repository = repo,
                path = artifact.key,
                parseable = true,
                component = result.metadata.component,
                variants = result.metadata.variants,
            )
            is ParseResult.Unparseable -> ModuleMetadataResponse(repo, artifact.key, parseable = false)
        }
    }

    @GetMapping("/repositories/{repo}/file/**")
    fun file(@PathVariable repo: String, request: HttpServletRequest): FileDetails {
        registry.require(repo)
        val artifact = RepositoryPath.of(trailing(request, "/api/repositories/$repo/file"))
        val parent = artifact.key.substringBeforeLast('/', "")
        val parentPrefix = if (parent.isEmpty()) repo else "$repo/$parent"
        val name = artifact.key.substringAfterLast('/')
        val entry = storage.list(parentPrefix).firstOrNull { !it.isDirectory && it.name == name }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no such file")
        return FileDetails(
            repository = repo,
            path = artifact.key,
            size = entry.sizeBytes ?: 0,
            lastModified = entry.lastModified,
            checksums = readChecksums("$repo/${artifact.key}"),
            downloadUrl = "/$repo/${artifact.key}",
        )
    }

    @DeleteMapping("/repositories/{repo}/**")
    fun delete(@PathVariable repo: String, request: HttpServletRequest): ResponseEntity<Void> {
        registry.require(repo)
        val key = "$repo/${RepositoryPath.of(trailing(request, "/api/repositories/$repo")).key}"
        val removed = if (storage.exists(key)) storage.delete(key) else storage.deletePrefix(key) > 0
        if (removed) logger.info { "Deleted $key" }
        return if (removed) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()
    }

    private fun readChecksums(key: String): Map<String, String> =
        CHECKSUM_EXTS.mapNotNull { ext ->
            storage.openRead("$key.$ext")?.let { stored ->
                ext to stored.stream.use { it.readBytes().decodeToString().trim() }
            }
        }.toMap()

    private fun trailing(request: HttpServletRequest, marker: String): String {
        val full = URLDecoder.decode(request.requestURI.removePrefix(request.contextPath), StandardCharsets.UTF_8)
        return full.substringAfter(marker).trimStart('/')
    }

    @ExceptionHandler(InvalidRepositoryPathException::class)
    fun handleInvalidPath(e: InvalidRepositoryPathException): ResponseEntity<String> =
        ResponseEntity.badRequest().body(e.message)

    @ExceptionHandler(RepositoryNotFoundException::class)
    fun handleUnknownRepository(e: RepositoryNotFoundException): ResponseEntity<String> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.message)

    private companion object {
        val CHECKSUM_EXTS = listOf("sha1", "md5", "sha256", "sha512")
        const val COORDINATE_MIN_SEGMENTS = 2
        val COORDINATE_EXTS = listOf(".pom", ".jar", ".module")
    }
}
