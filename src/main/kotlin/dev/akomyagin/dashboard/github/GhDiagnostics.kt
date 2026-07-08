package dev.akomyagin.dashboard.github

/**
 * One-shot startup diagnostics for the `gh` CLI.
 *
 * [GhCliClient] already degrades per-repo — every repo carries its own
 * [RepoStatus.error] and a single flaky repo never takes down the page. But when
 * *every* repo fails for the same reason (gh not installed, or `gh auth` not
 * logged in), printing nine identical error rows is noise that buries the one
 * thing the user needs to do. This helper detects that "all repos errored for the
 * same environmental cause" case and produces a single friendly hint instead.
 *
 * Kept as a pure function of the status list so it is unit-testable without
 * spawning processes or capturing stderr; [Main] decides when/where to print it.
 */
object GhDiagnostics {

    /**
     * Return a single human hint if the whole portfolio is degraded by what looks
     * like a missing or unauthenticated `gh` CLI, otherwise `null` (partial
     * failures are left to the normal per-repo error rendering).
     */
    fun hint(statuses: List<RepoStatus>): String? {
        if (statuses.isEmpty()) return null
        // Only speak up when the *entire* portfolio is down — a mix of ok/errored
        // repos is a real per-repo condition, not an environment problem.
        if (statuses.any { it.error == null }) return null

        val errors = statuses.mapNotNull { it.error }
        val looksLikeGhMissing = errors.any { it.looksLikeMissingGh() }
        val looksLikeNotAuthed = errors.any { it.looksLikeAuthFailure() }
        if (!looksLikeGhMissing && !looksLikeNotAuthed) return null

        return "Ни один репозиторий не удалось опросить. Похоже, gh CLI не установлен " +
            "или не выполнен вход — установите GitHub CLI и выполните `gh auth login`, " +
            "затем перезапустите. (Локальный TODO-скан продолжит работать по копиям на диске.)"
    }

    private fun String.looksLikeMissingGh(): Boolean {
        val s = lowercase()
        return "cannot run" in s ||
            "command not found" in s ||
            "no such file" in s ||
            ("not found" in s && "gh" in s)
    }

    private fun String.looksLikeAuthFailure(): Boolean {
        val s = lowercase()
        // Bare "auth" also matches unrelated gh errors that happen to contain
        // "author" (e.g. "could not resolve author"), so match narrower,
        // auth-specific fragments instead.
        return "not logged" in s ||
            "gh auth login" in s ||
            "authentication" in s ||
            "http 401" in s ||
            "unauthorized" in s
    }
}
