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

    // Matches a marker only when it is a standalone word inside a comment-ish
    // context, e.g. `// TODO: x`, `# FIXME x`, `<!-- HACK -->`. The leading
    // boundary avoids matching identifiers like `TODOLIST`.
    private val markerRegex = Regex(
        pattern = "(?<![A-Za-z0-9_])(${config.markers.joinToString("|") { Regex.escape(it) }})\\b[:\\-]?\\s*(.*)",
    )

    fun scan(): List<TodoItem> = config.repos.flatMap { scanRepo(it) }

    fun scanRepo(repo: RepoConfig): List<TodoItem> {
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
        lines.forEachIndexed { idx, line ->
            val match = markerRegex.find(line) ?: return@forEachIndexed
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
}
