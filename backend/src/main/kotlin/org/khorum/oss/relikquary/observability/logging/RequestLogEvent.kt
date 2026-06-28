package org.khorum.oss.relikquary.observability.logging

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * The structured per-request record (feature 010, FR-005). Serialized to one JSON line on the
 * `relikquary.access` logger. `repository` is null for non-repo paths and `principal` is null for
 * anonymous requests; both are omitted from the JSON when null (so an anonymous request has no
 * `principal` field).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class RequestLogEvent(
    val method: String,
    val repository: String?,
    val path: String,
    val status: Int,
    val bytes: Long,
    val durationMs: Long,
    val principal: String?,
)
