package dev.akomyagin.dashboard

import dev.akomyagin.dashboard.cache.StatusCache
import dev.akomyagin.dashboard.cache.mergeWithCache
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
    private val cache: StatusCache? = null,
) {
    /**
     * Fetch every repo's status; how that's batched/concurrent is [GitHubClient]'s
     * concern. When a [cache] is wired, the fresh poll is reconciled against the
     * last-known-good snapshot: degraded repos fall back to their cached status
     * (flagged `stale`), and CI transitions since the previous poll are flagged
     * `ciChanged`. Cache read/write failures degrade silently to "no cache".
     * When a cache is wired this also persists the merged snapshot to disk on
     * every call — not a pure read.
     */
    suspend fun portfolioStatus(): List<RepoStatus> {
        val fresh = github.fetchStatuses(config.repos)
        val cache = this.cache ?: return fresh
        val (display, updated) = mergeWithCache(fresh, cache.load())
        cache.save(updated)
        return display
    }

    /** Scan every repo on disk and return TODO items ranked by priority. */
    suspend fun todos(): List<TodoItem> = ranker.rank(scanner.scan())
}
