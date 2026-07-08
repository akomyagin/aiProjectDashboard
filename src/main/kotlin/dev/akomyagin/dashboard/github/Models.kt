package dev.akomyagin.dashboard.github

import kotlinx.serialization.Serializable

/** Normalized CI conclusion, decoupled from GitHub's raw string vocabulary. */
enum class CiStatus { SUCCESS, FAILURE, PENDING, UNKNOWN }

@Serializable
data class CommitInfo(
    val sha: String,
    val message: String,
    val author: String,
    val date: String,
)

/**
 * Everything the dashboard shows for one repository. [error] is non-null when
 * the GitHub lookup failed — the UI degrades gracefully per-repo rather than
 * failing the whole page.
 */
@Serializable
data class RepoStatus(
    val name: String,
    val fullName: String,
    val ci: CiStatus = CiStatus.UNKNOWN,
    val ciDetail: String? = null,
    val lastCommit: CommitInfo? = null,
    val openPrs: Int = 0,
    val error: String? = null,
)
