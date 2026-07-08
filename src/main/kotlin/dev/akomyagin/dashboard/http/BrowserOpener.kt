package dev.akomyagin.dashboard.http

import java.awt.Desktop
import java.net.URI

/**
 * Best-effort "open the dashboard in the user's default browser" on startup,
 * gated behind an explicit opt-in (the `--open` flag) — never the default, since
 * a headless/CLI run must not try to pop a browser.
 *
 * Opening a browser is inherently environment-dependent, so this is strictly
 * best-effort: it MUST NOT throw or crash the server. On headless Linux
 * `Desktop` is frequently unavailable, so we fall back to the platform "open"
 * command (`xdg-open`/`open`/`start`) and, if even that fails, just log and
 * carry on — the server is already serving; the user can open the URL manually.
 */
object BrowserOpener {

    /**
     * Attempt to open [url]. Returns `true` if a mechanism was launched, `false`
     * if none worked; either way it never propagates an exception.
     */
    fun open(url: String): Boolean {
        if (tryDesktop(url)) return true
        if (tryPlatformCommand(url)) return true
        System.err.println(
            "Не удалось открыть браузер автоматически — откройте $url вручную.",
        )
        return false
    }

    private fun tryDesktop(url: String): Boolean = runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
            true
        } else {
            false
        }
    }.getOrDefault(false)

    private fun tryPlatformCommand(url: String): Boolean {
        val os = System.getProperty("os.name")?.lowercase().orEmpty()
        val command = when {
            "win" in os -> listOf("cmd", "/c", "start", "", url)
            "mac" in os || "darwin" in os -> listOf("open", url)
            else -> listOf("xdg-open", url) // Linux / other Unix
        }
        return runCatching {
            ProcessBuilder(command).start()
            true
        }.getOrDefault(false)
    }
}
