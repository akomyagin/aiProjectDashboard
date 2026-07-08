package dev.akomyagin.dashboard.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * A single repository the dashboard aggregates. Both a GitHub coordinate
 * (owner/name, for CI + commits) and an optional local path (for the TODO
 * scanner) are captured. Nothing about the portfolio is hard-coded — the whole
 * list lives in [AppConfig] and is loaded from a JSON file on disk.
 */
@Serializable
data class RepoConfig(
    val name: String,
    val owner: String = "akomyagin",
    /** Absolute or ~-relative local path used by the TODO/FIXME scanner. */
    @SerialName("local_path")
    val localPath: String? = null,
    /** Default branch used when the API needs one; GitHub's default is resolved at runtime if null. */
    val branch: String? = null,
) {
    val fullName: String get() = "$owner/$name"
}

@Serializable
data class AppConfig(
    val repos: List<RepoConfig> = emptyList(),
    /** Marker keywords the scanner treats as actionable comments. */
    val markers: List<String> = listOf("TODO", "FIXME", "HACK", "XXX"),
    /** File extensions the scanner walks (leading dot included). */
    @SerialName("scan_extensions")
    val scanExtensions: List<String> = listOf(".kt", ".go", ".ts", ".tsx", ".py", ".php"),
    /** Directory names skipped during the filesystem walk. */
    @SerialName("scan_exclude_dirs")
    val scanExcludeDirs: List<String> =
        listOf(".git", "node_modules", "build", "dist", "target", ".gradle", ".venv", "vendor"),
    /** HTTP port the local web dashboard binds to (loopback only). */
    val port: Int = 8087,
) {
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }

        /**
         * Load config from [path]. If the file does not exist, a built-in default
         * portfolio is returned so the app is runnable out of the box (Stage 0).
         */
        fun load(path: Path): AppConfig {
            if (!path.exists()) return default()
            val text = Files.readString(path)
            return json.decodeFromString<AppConfig>(text)
        }

        fun serialize(config: AppConfig): String = json.encodeToString(serializer(), config)

        /** Built-in default portfolio — a convenience seed, not a hard dependency. */
        fun default(): AppConfig = AppConfig(
            repos = listOf(
                "aiProjectDashboard", "shelf", "gitl", "orm-nplus1-radar",
                "aiTelegaBot", "aiBrowserPlagin", "aiCostTracker",
                "aiMCPGate", "aiGitWorktreeManager",
            ).map { RepoConfig(name = it, localPath = "~/Projects/ai-projects/$it") },
        )
    }
}
