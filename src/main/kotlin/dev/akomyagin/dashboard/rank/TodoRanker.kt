package dev.akomyagin.dashboard.rank

import dev.akomyagin.dashboard.scan.TodoItem

/**
 * Port for prioritizing scanned TODO items. The LLM-backed implementation ranks
 * by urgency/relevance; the offline heuristic implementation keeps the whole app
 * runnable with no API key (mirroring the portfolio's offline-mode convention).
 */
interface TodoRanker {
    suspend fun rank(items: List<TodoItem>): List<TodoItem>
}

/**
 * Deterministic, no-network ranker. Scores by marker severity and a few textual
 * urgency signals, then sorts descending. Good enough to be useful and to keep
 * CI/dev fully offline; the LLM ranker (post-Stage-3) replaces it when a key is set.
 */
class HeuristicRanker : TodoRanker {

    private val markerWeight = mapOf(
        "FIXME" to 4, "XXX" to 4, "HACK" to 3, "TODO" to 2,
    )

    private val urgencyWords = listOf(
        "security", "leak", "crash", "critical", "urgent", "broken", "data loss",
        "race", "deadlock", "vuln", "cve", "asap", "important", "bug",
    )

    override suspend fun rank(items: List<TodoItem>): List<TodoItem> =
        items
            .map { it.copy(priority = score(it), reason = "heuristic") }
            .sortedByDescending { it.priority }

    private fun score(item: TodoItem): Int {
        val base = markerWeight[item.marker.uppercase()] ?: 1
        val lower = item.text.lowercase()
        val bonus = urgencyWords.count { lower.contains(it) }
        return (base + bonus).coerceIn(1, 5)
    }
}
