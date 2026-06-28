package org.khorum.oss.relikquary.config

import org.khorum.oss.relikquary.repository.RepositoryKind
import org.khorum.oss.relikquary.repository.RepositoryType
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The set of named repositories Relikquary serves. Each request is addressed by a repository name
 * prefix (`/{repo}/…`); there is no implicit repository at the root. A repository's [Repo.kind]
 * selects how it resolves: HOSTED stores locally (feature 004), PROXY caches an upstream, and GROUP
 * aggregates members (feature 006).
 */
@ConfigurationProperties(prefix = "relikquary")
data class RepositoryProperties(
    val repositories: List<Repo> = emptyList(),
) {
    data class Repo(
        val name: String = "",
        val kind: RepositoryKind = RepositoryKind.HOSTED,
        /** HOSTED acceptance/mutability policy; ignored for PROXY/GROUP. */
        val type: RepositoryType = RepositoryType.MIXED,
        /** PROXY: upstream Maven-layout base URL (e.g. https://repo1.maven.org/maven2). */
        val remoteUrl: String? = null,
        /** PROXY: optional upstream Basic-auth user. */
        val remoteUsername: String? = null,
        /** PROXY: optional upstream Basic-auth secret — supply via env/file, never commit it. */
        val remotePassword: String? = null,
        /** GROUP: ordered member repository names, resolved by first match. */
        val members: List<String> = emptyList(),
        /** Optional per-action access policy (feature 007); null ⇒ defaults preserve current behaviour. */
        val access: RepositoryAccess? = null,
    )

    /**
     * Per-repository authorization grants (feature 007). Each list holds principals — a username, or a
     * role written `@role`. A null list means the default for that action: READ open; PUBLISH/DELETE
     * gated by the global `PUBLISH` role. An explicit list overrides the default for that action.
     */
    data class RepositoryAccess(
        val read: List<String>? = null,
        val publish: List<String>? = null,
        val delete: List<String>? = null,
    )
}
