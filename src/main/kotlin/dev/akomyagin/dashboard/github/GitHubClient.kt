package dev.akomyagin.dashboard.github

import dev.akomyagin.dashboard.config.RepoConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Port for fetching repository status from GitHub. Kept as an interface so the
 * concrete `gh`-CLI implementation can be swapped for a REST implementation, and
 * so tests can inject a deterministic fake (see the testing tier in SKILL.md).
 */
interface GitHubClient {
    suspend fun fetchStatus(repo: RepoConfig): RepoStatus

    /**
     * Batch variant used to poll the whole portfolio. The default fans
     * [fetchStatus] out concurrently — the original Stage-1 behavior, and what
     * test fakes get for free without overriding anything. [GhCliClient]
     * overrides this with a single batched GraphQL query (Post-MVP: GraphQL
     * batching) so a live poll costs one `gh` subprocess instead of N.
     */
    suspend fun fetchStatuses(repos: List<RepoConfig>): List<RepoStatus> = coroutineScope {
        repos.map { repo -> async { fetchStatus(repo) } }.awaitAll()
    }
}
