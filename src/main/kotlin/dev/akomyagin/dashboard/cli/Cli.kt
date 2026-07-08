package dev.akomyagin.dashboard.cli

import dev.akomyagin.dashboard.DashboardService
import dev.akomyagin.dashboard.github.CiStatus
import kotlinx.coroutines.runBlocking

/**
 * Terminal renderer for the same data the web UI shows. Lets the tool be used
 * headless (`--cli status` / `--cli todos`) without opening a browser.
 */
object Cli {
    fun printStatus(service: DashboardService) = runBlocking {
        val statuses = service.portfolioStatus()
        println("Portfolio status (${statuses.size} repos)\n")
        statuses.forEach { s ->
            val icon = when (s.ci) {
                CiStatus.SUCCESS -> "OK  "
                CiStatus.FAILURE -> "FAIL"
                CiStatus.PENDING -> "...."
                CiStatus.UNKNOWN -> "?   "
            }
            val commit = s.lastCommit?.let { "${it.sha} ${it.message.take(50)}" } ?: "-"
            val err = s.error?.let { "  (error: $it)" } ?: ""
            println("[$icon] ${s.fullName.padEnd(34)} PRs:${s.openPrs}  $commit$err")
        }
    }

    fun printTodos(service: DashboardService) = runBlocking {
        val todos = service.todos()
        println("Cross-repo TODOs (${todos.size} items, ranked)\n")
        todos.take(50).forEach { t ->
            println("P${t.priority} [${t.marker}] ${t.repo}/${t.file}:${t.line}  ${t.text.take(80)}")
        }
    }
}
