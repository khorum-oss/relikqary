package org.khorum.oss.relikquary.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Global retention/cleanup settings (feature 009). Cleanup is off by default; enabling it schedules a
 * recurring run every [interval]. Per-repository retention/eviction policies live on each repository
 * (see [RepositoryProperties.RetentionPolicy]); a repository with no policy is never cleaned.
 */
@ConfigurationProperties(prefix = "relikquary.cleanup")
data class CleanupProperties(
    val enabled: Boolean = false,
    val interval: Duration = Duration.ofHours(1),
)
