package dev.akomyagin.dashboard

import dev.akomyagin.dashboard.config.AppConfig
import dev.akomyagin.dashboard.config.RepoConfig
import dev.akomyagin.dashboard.rank.HeuristicRanker
import dev.akomyagin.dashboard.scan.TodoScanner
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TodoScannerTest {

    private fun tempRepo(): Pair<AppConfig, String> {
        val dir = Files.createTempDirectory("scan-test")
        dir.resolve("a.kt").writeText(
            """
            fun x() {
                // TODO: refactor this
                // FIXME critical security leak in auth
                val notODOList = 1 // should not match TODOLIST-like tokens
            }
            """.trimIndent(),
        )
        // Excluded dir must be skipped.
        val nm = dir.resolve("node_modules")
        Files.createDirectories(nm)
        nm.resolve("b.ts").writeText("// TODO: ignored")

        val cfg = AppConfig(
            repos = listOf(RepoConfig(name = "sample", localPath = dir.toString())),
        )
        return cfg to dir.toString()
    }

    @Test
    fun `finds markers and skips excluded dirs and non-markers`() {
        val (cfg, _) = tempRepo()
        val items = TodoScanner(cfg).scan()
        assertEquals(2, items.size, "should find exactly the TODO and FIXME, not node_modules or TODOLIST")
        assertTrue(items.any { it.marker == "TODO" })
        assertTrue(items.any { it.marker == "FIXME" })
    }

    @Test
    fun `ranker prioritizes security fixme over plain todo`() = runTest {
        val (cfg, _) = tempRepo()
        val ranked = HeuristicRanker().rank(TodoScanner(cfg).scan())
        assertEquals("FIXME", ranked.first().marker, "security FIXME should rank first")
        assertTrue(ranked.first().priority >= ranked.last().priority)
    }
}
