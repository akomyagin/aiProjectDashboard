package dev.akomyagin.dashboard.github

import dev.akomyagin.dashboard.config.RepoConfig

/**
 * Port for fetching repository status from GitHub. Kept as an interface so the
 * concrete `gh`-CLI implementation can be swapped for a REST implementation, and
 * so tests can inject a deterministic fake (see the testing tier in SKILL.md).
 */
interface GitHubClient {
    suspend fun fetchStatus(repo: RepoConfig): RepoStatus
}
