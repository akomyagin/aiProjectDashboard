package dev.akomyagin.dashboard

import dev.akomyagin.dashboard.github.GhDiagnostics
import dev.akomyagin.dashboard.github.RepoStatus
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GhDiagnosticsTest {

    private fun errored(name: String, error: String) =
        RepoStatus(name = name, fullName = "akomyagin/$name", error = error)

    private fun ok(name: String) =
        RepoStatus(name = name, fullName = "akomyagin/$name")

    @Test
    fun `no hint when list is empty`() {
        assertNull(GhDiagnostics.hint(emptyList()))
    }

    @Test
    fun `no hint when at least one repo succeeded`() {
        val statuses = listOf(
            ok("a"),
            errored("b", "cannot run 'gh': No such file or directory"),
        )
        assertNull(GhDiagnostics.hint(statuses))
    }

    @Test
    fun `hint when all repos fail with gh-missing errors`() {
        val statuses = listOf(
            errored("a", "cannot run 'gh': No such file or directory"),
            errored("b", "cannot run 'gh': No such file or directory"),
        )
        val hint = GhDiagnostics.hint(statuses)
        assertNotNull(hint)
        assert(hint.contains("gh auth login"))
    }

    @Test
    fun `hint when all repos fail with auth errors`() {
        val statuses = listOf(
            errored("a", "gh failed: authentication required, run gh auth login"),
            errored("b", "gh failed: HTTP 401 Unauthorized"),
        )
        assertNotNull(GhDiagnostics.hint(statuses))
    }

    @Test
    fun `no hint when all fail but for unrelated reasons`() {
        // e.g. all repos genuinely missing on GitHub — not an environment issue.
        val statuses = listOf(
            errored("a", "gh failed: could not resolve to a Repository"),
            errored("b", "gh timed out after 20s: run list"),
        )
        assertNull(GhDiagnostics.hint(statuses))
    }

    @Test
    fun `no hint when the whole portfolio has a bad owner-name in config, not a missing gh`() {
        // Regression: the GraphQL batch client's per-repo "not found" message must
        // not be misread by looksLikeMissingGh() as "gh CLI isn't installed".
        val statuses = listOf(
            errored("a", "repository not found or inaccessible"),
            errored("b", "repository not found or inaccessible"),
        )
        assertNull(GhDiagnostics.hint(statuses))
    }
}
