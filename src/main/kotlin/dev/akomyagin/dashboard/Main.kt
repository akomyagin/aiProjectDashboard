package dev.akomyagin.dashboard

import dev.akomyagin.dashboard.cli.Cli
import dev.akomyagin.dashboard.config.AppConfig
import dev.akomyagin.dashboard.config.expandHome
import dev.akomyagin.dashboard.github.GhCliClient
import dev.akomyagin.dashboard.http.dashboardServer
import dev.akomyagin.dashboard.rank.HeuristicRanker

/**
 * Entry point. Two run modes:
 *   (default)        start the local web dashboard on 127.0.0.1:<port>
 *   --cli status     print portfolio CI/commit status to the terminal and exit
 *   --cli todos      print the ranked cross-repo TODO list and exit
 *
 * Config path defaults to ~/.config/aiProjectDashboard/config.json; override with
 * --config <path>. A missing config falls back to the built-in default portfolio.
 */
fun main(args: Array<String>) {
    val configPath = argValue(args, "--config")
        ?.let { expandHome(it) }
        ?: expandHome("~/.config/aiProjectDashboard/config.json")
    val config = AppConfig.load(configPath)

    val service = DashboardService(
        config = config,
        github = GhCliClient(),
        ranker = HeuristicRanker(),
    )

    when (argValue(args, "--cli")) {
        "status" -> Cli.printStatus(service)
        "todos" -> Cli.printTodos(service)
        null -> {
            println("aiProjectDashboard → http://127.0.0.1:${config.port}  (Ctrl-C to stop)")
            dashboardServer(service, config.port).start(wait = true)
        }
        else -> {
            System.err.println("Unknown --cli mode. Use: status | todos")
            kotlin.system.exitProcess(2)
        }
    }
}

private fun argValue(args: Array<String>, flag: String): String? {
    val i = args.indexOf(flag)
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}
