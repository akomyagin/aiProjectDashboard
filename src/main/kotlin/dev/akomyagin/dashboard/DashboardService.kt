package dev.akomyagin.dashboard

import dev.akomyagin.dashboard.config.AppConfig
import dev.akomyagin.dashboard.github.GitHubClient
import dev.akomyagin.dashboard.github.RepoStatus
import dev.akomyagin.dashboard.rank.TodoRanker
import dev.akomyagin.dashboard.scan.TodoItem
import dev.akomyagin.dashboard.scan.TodoScanner

/**
 * Application core: orchestrates the two MVP capabilities.
 *  - Phase 1: [portfolioStatus] — CI + last commit + open PRs per repo.
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
    /** Fetch every repo's status; how that's batched/concurrent is [GitHubClient]'s concern. */
    suspend fun portfolioStatus(): List<RepoStatus> = github.fetchStatuses(config.repos)

    /** Scan every repo on disk and return TODO items ranked by priority. */
    suspend fun todos(): List<TodoItem> = ranker.rank(scanner.scan())
}
