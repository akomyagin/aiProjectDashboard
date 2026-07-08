package dev.akomyagin.dashboard.scan

import dev.akomyagin.dashboard.config.AppConfig
import dev.akomyagin.dashboard.config.RepoConfig
import dev.akomyagin.dashboard.config.expandHome
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** One TODO/FIXME/HACK comment found on disk. */
@Serializable
data class TodoItem(
    val repo: String,
    val marker: String,
    val text: String,
    val file: String,
    val line: Int,
    /** AI-assigned priority 1..5 (5 = most urgent); 0 until ranked. */
    val priority: Int = 0,
    val reason: String? = null,
)

/**
 * Walks each repo's local working tree collecting marker comments. Pure
 * filesystem + regex; no git and no network, so it is fully deterministic and
 * unit-testable against a temp directory.
 */
class TodoScanner(private val config: AppConfig) {

    private val markerAlternation = config.markers.joinToString("|") { Regex.escape(it) }

    fun scan(): List<TodoItem> = config.repos.flatMap { scanRepo(it) }

    fun scanRepo(repo: RepoConfig): List<TodoItem> {
        // A repo with no local_path, or whose working copy isn't on this machine,
        // is silently skipped (empty list) rather than erroring: the portfolio is
        // shared across machines and a repo not checked out here simply has no
        // local TODOs to contribute. This is intentional, not a swallowed failure.
        val root = repo.localPath?.let { expandHome(it) } ?: return emptyList()
        if (!root.exists()) return emptyList()
        val results = mutableListOf<TodoItem>()
        Files.walk(root).use { stream ->
            stream
                .filter { it.isRegularFile() }
                .filter { path -> !isExcluded(root, path) }
                .filter { path -> config.scanExtensions.any { path.name.endsWith(it) } }
                .forEach { path -> results += scanFile(repo.name, root, path) }
        }
        return results
    }

    private fun isExcluded(root: Path, path: Path): Boolean {
        val rel = root.relativize(path)
        return (0 until rel.nameCount).any { rel.getName(it).toString() in config.scanExcludeDirs }
    }

    private fun scanFile(repoName: String, root: Path, path: Path): List<TodoItem> {
        val items = mutableListOf<TodoItem>()
        val relFile = root.relativize(path).toString()
        val lines = runCatching { Files.readAllLines(path) }.getOrNull() ?: return emptyList()
        val regex = commentMarkerRegex(path.name)
        lines.forEachIndexed { idx, line ->
            val match = regex.find(line) ?: return@forEachIndexed
            val marker = match.groupValues[1]
            val text = match.groupValues[2].trim().ifEmpty { line.trim() }
            items += TodoItem(
                repo = repoName,
                marker = marker,
                text = text.take(300),
                file = relFile,
                line = idx + 1,
            )
        }
        return items
    }

    // A marker only counts when it directly follows a comment opener on the
    // same line, with only whitespace allowed in between: a language
    // line-comment token, a block-comment opener, or a block-comment
    // continuation line starting with `*`. That's what keeps marker words
    // that merely appear in string literals or descriptive prose (including
    // this class's own doc comments) from being counted as real hits.
    private fun commentMarkerRegex(fileName: String): Regex {
        val lineTokens = when {
            fileName.endsWith(".py") -> listOf("#")
            fileName.endsWith(".php") -> listOf("//", "#")
            else -> listOf("//") // .kt, .go, .ts, .tsx
        }
        val openers = (listOf("^\\s*\\*") + lineTokens.map { Regex.escape(it) } + listOf("/\\*"))
            .joinToString("|")
        return Regex("(?:$openers)\\s*($markerAlternation)\\b[:\\-]?\\s*(.*)")
    }
}
