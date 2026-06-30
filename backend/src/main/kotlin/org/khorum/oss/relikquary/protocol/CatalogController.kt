package org.khorum.oss.relikquary.protocol

import org.khorum.oss.relikquary.catalog.CatalogService
import org.khorum.oss.relikquary.protocol.dto.CatalogResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Cross-repo artifact catalog (feature 016, Phase 2): a paginated, optionally-filtered list of
 * `group:artifact` entries powering the catalog view and topbar search. Reads are scoped to the
 * repositories the caller may READ (enforced in [CatalogService]); `q` filters by `group:artifact`
 * substring server-side. Additive `/api` surface — the Maven protocol and browse API are unchanged.
 */
@RestController
@RequestMapping("/api")
class CatalogController(private val catalog: CatalogService) {

    @GetMapping("/catalog")
    fun catalog(
        @RequestParam(required = false) repo: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "$DEFAULT_PAGE_SIZE") pageSize: Int,
    ): CatalogResponse {
        val authentication = SecurityContextHolder.getContext().authentication
        val matched = catalog.entries(repo, authentication)
            .let { all ->
                val needle = q?.trim()?.lowercase()
                if (needle.isNullOrEmpty()) all
                else all.filter { "${it.group}:${it.artifact}".lowercase().contains(needle) }
            }
            .sortedBy { "${it.group}:${it.artifact}" }

        val size = pageSize.coerceIn(1, MAX_PAGE_SIZE)
        val safePage = page.coerceAtLeast(0)
        val from = (safePage * size).coerceAtMost(matched.size)
        val to = (from + size).coerceAtMost(matched.size)
        return CatalogResponse(
            entries = matched.subList(from, to),
            page = safePage,
            pageSize = size,
            total = matched.size.toLong(),
            truncated = to < matched.size,
        )
    }

    private companion object {
        const val DEFAULT_PAGE_SIZE = 100
        const val MAX_PAGE_SIZE = 500
    }
}
