package org.khorum.oss.relikquary.repository

/**
 * How a repository resolves requests and whether it accepts publishes (feature 006):
 * - [HOSTED]: stores artifacts locally; accepts publishes per its [RepositoryType] (feature 004).
 * - [PROXY]: read-only; fetches from a configured upstream on a cache miss and caches the bytes.
 * - [GROUP]: read-only; aggregates ordered member repositories, returning the first match.
 */
enum class RepositoryKind { HOSTED, PROXY, GROUP }
