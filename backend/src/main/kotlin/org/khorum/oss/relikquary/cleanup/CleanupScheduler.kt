package org.khorum.oss.relikquary.cleanup

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * Runs cleanup on a fixed schedule when `relikquary.cleanup.enabled=true` (feature 009). The delay binds
 * the `relikquary.cleanup.interval` ISO-8601 `Duration` directly (`fixedDelayString` accepts a Duration
 * string). Disabled by default, so existing deployments schedule nothing.
 */
@Component
@ConditionalOnProperty(name = ["relikquary.cleanup.enabled"], havingValue = "true")
class CleanupScheduler(private val service: CleanupService) {

    @Scheduled(fixedDelayString = "\${relikquary.cleanup.interval:PT1H}")
    fun runScheduled() {
        val report = service.run(dryRun = false)
        logger.info { "Scheduled cleanup removed ${report.itemsRemoved} files (${report.bytesReclaimed} bytes)" }
    }
}
