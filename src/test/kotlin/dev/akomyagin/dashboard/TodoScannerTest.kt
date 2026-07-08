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
            /** Handles marker words used by the TODO/FIXME scanner in prose, which must not match. */
            fun x() {
                // TODO: refactor this
                // FIXME critical security leak in auth
                val notODOList = 1 // should not match TODOLIST-like tokens
                val s = "the TODO/FIXME scanner mentioned in a string literal"
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
    fun `finds markers and skips excluded dirs, non-markers, strings and prose`() {
        val (cfg, _) = tempRepo()
        val items = TodoScanner(cfg).scan()
        assertEquals(
            2,
            items.size,
            "should find exactly the TODO and FIXME comments, not node_modules, TODOLIST, " +
                "the KDoc prose mention, or the string literal mention",
        )
        assertTrue(items.any { it.marker == "TODO" })
        assertTrue(items.any { it.marker == "FIXME" })
    }

    @Test
    fun `marker inside a string literal does not match`() {
        val dir = Files.createTempDirectory("scan-test-string")
        dir.resolve("a.kt").writeText("val note = \"remember the TODO/FIXME scanner behavior\"\n")
        val cfg = AppConfig(repos = listOf(RepoConfig(name = "sample", localPath = dir.toString())))
        assertEquals(emptyList(), TodoScanner(cfg).scan())
    }

    @Test
    fun `marker in a real line comment matches`() {
        val dir = Files.createTempDirectory("scan-test-comment")
        dir.resolve("a.kt").writeText("// TODO: wire up retries\n")
        val cfg = AppConfig(repos = listOf(RepoConfig(name = "sample", localPath = dir.toString())))
        val items = TodoScanner(cfg).scan()
        assertEquals(1, items.size)
        assertEquals("TODO", items.first().marker)
        assertEquals("wire up retries", items.first().text)
    }

    @Test
    fun `marker in a block-comment continuation line matches`() {
        val dir = Files.createTempDirectory("scan-test-block")
        dir.resolve("a.kt").writeText(
            """
            /**
             * TODO: revisit ranking weights
             */
            """.trimIndent() + "\n",
        )
        val cfg = AppConfig(repos = listOf(RepoConfig(name = "sample", localPath = dir.toString())))
        val items = TodoScanner(cfg).scan()
        assertEquals(1, items.size)
        assertEquals("TODO", items.first().marker)
    }

    @Test
    fun `python uses hash comments and php accepts either token`() {
        val dir = Files.createTempDirectory("scan-test-lang")
        dir.resolve("a.py").writeText(
            """
            x = 1  # TODO: handle edge case
            note = "TODO in a string must not match"
            """.trimIndent() + "\n",
        )
        dir.resolve("a.php").writeText(
            """
            <?php
            // FIXME slash-style comment
            # HACK legacy workaround
            """.trimIndent() + "\n",
        )
        val cfg = AppConfig(repos = listOf(RepoConfig(name = "sample", localPath = dir.toString())))
        val items = TodoScanner(cfg).scan()
        assertEquals(3, items.size)
        assertTrue(items.any { it.marker == "TODO" && it.file.endsWith(".py") })
        assertTrue(items.any { it.marker == "FIXME" })
        assertTrue(items.any { it.marker == "HACK" })
    }

    @Test
    fun `ranker prioritizes security fixme over plain todo`() = runTest {
        val (cfg, _) = tempRepo()
        val ranked = HeuristicRanker().rank(TodoScanner(cfg).scan())
        assertEquals("FIXME", ranked.first().marker, "security FIXME should rank first")
        assertTrue(ranked.first().priority >= ranked.last().priority)
    }
}
