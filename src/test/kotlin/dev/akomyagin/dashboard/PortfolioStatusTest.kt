package dev.akomyagin.dashboard

import dev.akomyagin.dashboard.cli.Cli
import dev.akomyagin.dashboard.config.AppConfig
import dev.akomyagin.dashboard.config.RepoConfig
import dev.akomyagin.dashboard.github.CiStatus
import dev.akomyagin.dashboard.github.CommitInfo
import dev.akomyagin.dashboard.github.GitHubClient
import dev.akomyagin.dashboard.github.RepoStatus
import dev.akomyagin.dashboard.rank.HeuristicRanker
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deterministic fake — never shells out to `gh` or the network. Returns a canned
 * [RepoStatus] per repo name, letting us exercise the concurrent-poll, degraded,
 * and rendering paths hermetically.
 */
class FakeGitHubClient(
    private val byName: Map<String, RepoStatus> = emptyMap(),
    private val default: (RepoConfig) -> RepoStatus = { ok(it.name) },
) : GitHubClient {
    override suspend fun fetchStatus(repo: RepoConfig): RepoStatus =
        byName[repo.name] ?: default(repo)

    companion object {
        fun ok(name: String, prs: Int = 0, ci: CiStatus = CiStatus.SUCCESS) = RepoStatus(
            name = name,
            fullName = "akomyagin/$name",
            ci = ci,
            ciDetail = "CI",
            lastCommit = CommitInfo("abc1234", "do the thing", "alkom", "2026-07-01T10:00:00Z"),
            openPrs = prs,
        )

        fun failed(name: String, error: String) =
            RepoStatus(name = name, fullName = "akomyagin/$name", error = error)
    }
}

private fun configFor(vararg names: String) =
    AppConfig(repos = names.map { RepoConfig(name = it) })

class PortfolioStatusTest {

    @Test
    fun `polls every repo in the portfolio`() = runTest {
        val cfg = configFor("shelf", "gitl", "aiTelegaBot")
        val service = DashboardService(cfg, FakeGitHubClient(), HeuristicRanker(), scannerFor(cfg))

        val statuses = service.portfolioStatus()

        assertEquals(3, statuses.size)
        assertEquals(setOf("shelf", "gitl", "aiTelegaBot"), statuses.map { it.name }.toSet())
        assertTrue(statuses.all { it.error == null })
    }

    @Test
    fun `one unavailable repo degrades alone — the rest still report`() = runTest {
        val cfg = configFor("shelf", "gitl", "aiTelegaBot")
        val github = FakeGitHubClient(
            byName = mapOf("gitl" to FakeGitHubClient.failed("gitl", "gh failed: repository not found")),
        )
        val service = DashboardService(cfg, github, HeuristicRanker(), scannerFor(cfg))

        val statuses = service.portfolioStatus()

        val broken = statuses.single { it.name == "gitl" }
        assertEquals("gh failed: repository not found", broken.error)
        assertEquals(CiStatus.UNKNOWN, broken.ci)

        // The other two are unaffected — degradation is strictly per-repo.
        val healthy = statuses.filter { it.name != "gitl" }
        assertEquals(2, healthy.size)
        assertTrue(healthy.all { it.error == null && it.ci == CiStatus.SUCCESS })
    }

    @Test
    fun `cli render shows badges, counts and the degraded row`() {
        val statuses = listOf(
            FakeGitHubClient.ok("shelf", prs = 2, ci = CiStatus.SUCCESS),
            FakeGitHubClient.ok("gitl", ci = CiStatus.FAILURE),
            FakeGitHubClient.failed("orm-nplus1-radar", "gh not authenticated"),
        )

        val out = Cli.renderStatus(statuses)

        assertTrue(out.contains("3 repos (2 ok, 1 degraded)"), out)
        assertTrue(out.contains("OK"), out)
        assertTrue(out.contains("FAIL"), out)
        assertTrue(out.contains("error: gh not authenticated"), out)
        assertTrue(out.contains("akomyagin/shelf"), out)
    }

    @Test
    fun `empty portfolio renders header without crashing`() {
        val out = Cli.renderStatus(emptyList())
        assertTrue(out.contains("0 repos (0 ok, 0 degraded)"), out)
    }

    @Test
    fun `todos still empty when repos have no local paths`() = runTest {
        val cfg = configFor("shelf")
        val service = DashboardService(cfg, FakeGitHubClient(), HeuristicRanker(), scannerFor(cfg))
        assertTrue(service.todos().isEmpty())
    }

    private fun scannerFor(cfg: AppConfig) =
        dev.akomyagin.dashboard.scan.TodoScanner(cfg)

    @Test
    fun `commit info survives the fake round-trip`() = runTest {
        val cfg = configFor("shelf")
        val service = DashboardService(cfg, FakeGitHubClient(), HeuristicRanker(), scannerFor(cfg))
        val s = service.portfolioStatus().single()
        assertEquals("abc1234", s.lastCommit?.sha)
        assertNull(s.error)
    }
}
