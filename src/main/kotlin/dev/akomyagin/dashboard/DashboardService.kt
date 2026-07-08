package dev.akomyagin.dashboard

import dev.akomyagin.dashboard.config.AppConfig
import dev.akomyagin.dashboard.github.GitHubClient
import dev.akomyagin.dashboard.github.RepoStatus
import dev.akomyagin.dashboard.rank.TodoRanker
import dev.akomyagin.dashboard.scan.TodoItem
import dev.akomyagin.dashboard.scan.TodoScanner
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Application core: orchestrates the two MVP capabilities.
 *  - Phase 1: [portfolioStatus] — CI + last commit + open PRs per repo (concurrent).
 *  - Phase 2: [todos] — cross-repo TODO scan, AI-ranked.
 *
 * Depends only on ports ([GitHubClient], [TodoRanker]) so it is trivial to test
 * with fakes and equally usable from the web routes and the CLI.
 */
class DashboardService(
    private val config: AppConfig,
    private val github: GitHubClient,
    private val ranker: TodoRanker,
    private val scanner: TodoScanner = TodoScanner(config),
) {
    /** Fetch all repos' status concurrently. */
    suspend fun portfolioStatus(): List<RepoStatus> = coroutineScope {
        config.repos
            .map { repo -> async { github.fetchStatus(repo) } }
            .awaitAll()
    }

    /** Scan every repo on disk and return TODO items ranked by priority. */
    suspend fun todos(): List<TodoItem> = ranker.rank(scanner.scan())
}
