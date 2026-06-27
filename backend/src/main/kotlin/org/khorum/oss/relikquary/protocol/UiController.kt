package org.khorum.oss.relikquary.protocol

import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Serves the optionally-bundled SvelteKit UI (built into `classpath:/static/ui/` via
 * `-PbundleFrontend`) under the `ui` path prefix. Mapping the `ui` subtree explicitly makes it win
 * over the Maven catch-all by path specificity (same mechanism as the `api` controller), so the
 * repository routes are untouched. Unknown sub-paths fall back to `index.html` for SPA deep links;
 * when the UI was not bundled, requests return 404 — harmless.
 */
@RestController
class UiController {

    @GetMapping("/ui/**")
    fun serve(request: HttpServletRequest): ResponseEntity<Resource> {
        val relative = request.requestURI.removePrefix(request.contextPath).removePrefix("/ui").trimStart('/')
        if (relative.contains("..")) return ResponseEntity.badRequest().build()

        val direct = ClassPathResource("$BASE$relative")
        val resource = if (relative.isNotEmpty() && direct.exists() && direct.isReadable) direct else INDEX
        if (!resource.exists()) return ResponseEntity.notFound().build()

        val name = if (resource === INDEX) "index.html" else relative
        return ResponseEntity.ok().contentType(contentTypeFor(name)).body(resource)
    }

    private fun contentTypeFor(name: String): MediaType = when (name.substringAfterLast('.', "")) {
        "html" -> MediaType.TEXT_HTML
        "js" -> MediaType.valueOf("text/javascript;charset=UTF-8")
        "css" -> MediaType.valueOf("text/css;charset=UTF-8")
        "json", "map" -> MediaType.APPLICATION_JSON
        "svg" -> MediaType.valueOf("image/svg+xml")
        "ico" -> MediaType.valueOf("image/x-icon")
        "png" -> MediaType.IMAGE_PNG
        "woff2" -> MediaType.valueOf("font/woff2")
        "woff" -> MediaType.valueOf("font/woff")
        else -> MediaType.APPLICATION_OCTET_STREAM
    }

    private companion object {
        const val BASE = "static/ui/"
        val INDEX = ClassPathResource("${BASE}index.html")
    }
}
