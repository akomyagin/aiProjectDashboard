package dev.akomyagin.dashboard

import dev.akomyagin.dashboard.cli.Cli
import dev.akomyagin.dashboard.config.AppConfig
import dev.akomyagin.dashboard.config.InitConfigResult
import dev.akomyagin.dashboard.config.expandHome
import dev.akomyagin.dashboard.config.initConfig
import dev.akomyagin.dashboard.github.GhCliClient
import dev.akomyagin.dashboard.github.GhDiagnostics
import dev.akomyagin.dashboard.http.BrowserOpener
import dev.akomyagin.dashboard.http.dashboardServer
import dev.akomyagin.dashboard.rank.HeuristicRanker
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

/**
 * Entry point. Run modes:
 *   (default)        start the local web dashboard on 127.0.0.1:<port>
 *   --cli status     print portfolio CI/commit status to the terminal and exit
 *   --cli todos      print the ranked cross-repo TODO list and exit
 *   --init-config    write a starter config to the config path and exit
 *
 * Flags:
 *   --config <path>  override the config path (default ~/.config/aiProjectDashboard/config.json)
 *   --force          with --init-config, overwrite an existing config file
 *   --open           in web mode, open the dashboard URL in the default browser on start
 *
 * A missing config falls back to the built-in default portfolio; use --init-config
 * to materialize an editable copy on disk.
 */
fun main(args: Array<String>) {
    val configPath = argValue(args, "--config")
        ?.let { expandHome(it) }
        ?: expandHome("~/.config/aiProjectDashboard/config.json")

    // --init-config is a one-shot side effect that exits; handle it before loading.
    if (hasFlag(args, "--init-config")) {
        runInitConfig(configPath, force = hasFlag(args, "--force"))
        return
    }

    val config = AppConfig.load(configPath)

    val service = DashboardService(
        config = config,
        github = GhCliClient(),
        ranker = HeuristicRanker(),
    )

    when (argValue(args, "--cli")) {
        "status" -> Cli.printStatus(service) // prints its own gh diagnostic
        "todos" -> Cli.printTodos(service)
        null -> runWebMode(service, config.port, open = hasFlag(args, "--open"))
        else -> {
            System.err.println("Unknown --cli mode. Use: status | todos")
            kotlin.system.exitProcess(2)
        }
    }
}

/** Materialize a starter config on disk, print guidance, and set an exit code. */
private fun runInitConfig(configPath: java.nio.file.Path, force: Boolean) {
    when (val result = initConfig(configPath, force)) {
        is InitConfigResult.Written -> {
            val verb = if (result.overwritten) "перезаписан" else "создан"
            println("Конфиг $verb: ${result.path}")
            println("Отредактируйте список репозиториев (repos) под свой портфель и перезапустите.")
        }
        is InitConfigResult.Refused -> {
            System.err.println("Конфиг уже существует: ${result.path}")
            System.err.println("Он не был изменён. Добавьте --force, чтобы перезаписать (данные будут потеряны).")
            kotlin.system.exitProcess(1)
        }
    }
}

/**
 * Start the web server. If [open] is set, kick off a best-effort browser launch
 * on a background thread once the (blocking) server call is about to take over;
 * browser opening must never crash the server, so failures are swallowed inside
 * [BrowserOpener].
 */
private fun runWebMode(service: DashboardService, port: Int, open: Boolean) {
    val url = "http://127.0.0.1:$port"
    println("aiProjectDashboard → $url  (Ctrl-C to stop)")

    // Surface the gh diagnostic once, before the server blocks the main thread.
    warnIfPortfolioAllErrored(service)

    if (open) {
        // Off-thread so we don't delay start() and never block on a slow launcher.
        thread(isDaemon = true, name = "browser-opener") { BrowserOpener.open(url) }
    }
    dashboardServer(service, port).start(wait = true)
}

/**
 * Print a single friendly hint to stderr if the whole portfolio failed for what
 * looks like a missing/unauthenticated `gh` — instead of N identical error rows.
 * A no-op when things are fine or only some repos are degraded.
 */
private fun warnIfPortfolioAllErrored(service: DashboardService) {
    val statuses = runCatching { runBlocking { service.portfolioStatus() } }.getOrNull() ?: return
    GhDiagnostics.hint(statuses)?.let { System.err.println(it) }
}

private fun argValue(args: Array<String>, flag: String): String? {
    val i = args.indexOf(flag)
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}

private fun hasFlag(args: Array<String>, flag: String): Boolean = args.contains(flag)
