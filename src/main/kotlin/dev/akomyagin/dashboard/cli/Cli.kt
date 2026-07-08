package dev.akomyagin.dashboard.cli

import dev.akomyagin.dashboard.DashboardService
import dev.akomyagin.dashboard.github.CiStatus
import dev.akomyagin.dashboard.github.RepoStatus
import kotlinx.coroutines.runBlocking

/**
 * Terminal renderer for the same data the web UI shows. Lets the tool be used
 * headless (`--cli status` / `--cli todos`) without opening a browser.
 */
object Cli {
    fun printStatus(service: DashboardService) = runBlocking {
        val statuses = service.portfolioStatus()
        print(renderStatus(statuses))
    }

    /** Pure renderer, extracted so it can be unit-tested without stdout capture. */
    fun renderStatus(statuses: List<RepoStatus>): String {
        val sb = StringBuilder()
        val ok = statuses.count { it.error == null }
        val degraded = statuses.size - ok
        sb.append("Portfolio status — ${statuses.size} repos ($ok ok, $degraded degraded)\n\n")

        val nameWidth = (statuses.maxOfOrNull { it.fullName.length } ?: 20).coerceAtLeast(10)
        sb.append("  ${"CI".padEnd(6)} ${"Repository".padEnd(nameWidth)}  PRs  Last commit\n")
        sb.append("  ${"-".repeat(6)} ${"-".repeat(nameWidth)}  ---  ${"-".repeat(20)}\n")

        statuses.forEach { s ->
            val badge = when (s.ci) {
                CiStatus.SUCCESS -> "OK"
                CiStatus.FAILURE -> "FAIL"
                CiStatus.PENDING -> "RUN"
                CiStatus.UNKNOWN -> "?"
            }
            val commit = when {
                s.error != null -> "error: ${s.error}"
                s.lastCommit != null -> "${s.lastCommit.sha} ${s.lastCommit.message.take(60)}"
                else -> "-"
            }
            sb.append(
                "  ${badge.padEnd(6)} ${s.fullName.padEnd(nameWidth)}  ${s.openPrs.toString().padStart(3)}  $commit\n",
            )
        }
        return sb.toString()
    }

    fun printTodos(service: DashboardService) = runBlocking {
        val todos = service.todos()
        println("Cross-repo TODOs (${todos.size} items, ranked)\n")
        todos.take(50).forEach { t ->
            println("P${t.priority} [${t.marker}] ${t.repo}/${t.file}:${t.line}  ${t.text.take(80)}")
        }
    }
}
