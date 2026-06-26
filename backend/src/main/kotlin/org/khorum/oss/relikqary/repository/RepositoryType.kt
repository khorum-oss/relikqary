package org.khorum.oss.relikqary.repository

/**
 * A repository's type governs which coordinate kinds it accepts and their mutability (feature 004):
 * - [RELEASE]: only release versions; existing coordinates are immutable.
 * - [SNAPSHOT]: only `-SNAPSHOT` versions; existing coordinates are overwritable.
 * - [MIXED]: both, with the version-string mutability rule (release immutable, snapshot overwritable).
 */
enum class RepositoryType { RELEASE, SNAPSHOT, MIXED }
