package org.khorum.oss.relikquary.protocol

import org.khorum.oss.relikquary.cleanup.CleanupService
import org.khorum.oss.relikquary.protocol.dto.CleanupReport
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * On-demand cleanup endpoint (feature 009). `POST /api/cleanup` runs retention/eviction once and returns
 * the report; `dryRun=true` reports the selection without deleting. Requires the `PUBLISH` authority when
 * security is enabled (enforced by [org.khorum.oss.relikquary.security.RepositoryAuthorizationManager]).
 */
@RestController
@RequestMapping("/api")
class CleanupController(private val service: CleanupService) {

    @PostMapping("/cleanup")
    fun cleanup(@RequestParam(defaultValue = "false") dryRun: Boolean): CleanupReport = service.run(dryRun)
}
