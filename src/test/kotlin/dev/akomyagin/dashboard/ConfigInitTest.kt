package dev.akomyagin.dashboard

import dev.akomyagin.dashboard.config.AppConfig
import dev.akomyagin.dashboard.config.InitConfigResult
import dev.akomyagin.dashboard.config.initConfig
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Init-config logic runs against a @TempDir only — it must never touch the real
 * ~/.config/aiProjectDashboard on the developer's machine.
 */
class ConfigInitTest {

    @Test
    fun `init creates config and parent dirs when absent`(@TempDir dir: Path) {
        val target = dir.resolve("nested/config/config.json")
        assertFalse(target.exists())

        val result = initConfig(target)

        val written = assertIs<InitConfigResult.Written>(result)
        assertFalse(written.overwritten)
        assertEquals(target, written.path)
        assertTrue(target.exists(), "config file should be created")

        // The written content is a valid AppConfig matching the built-in default.
        val loaded = AppConfig.load(target)
        assertEquals(AppConfig.default().repos.size, loaded.repos.size)
    }

    @Test
    fun `init refuses to overwrite an existing file without force`(@TempDir dir: Path) {
        val target = dir.resolve("config.json")
        Files.writeString(target, """{"port": 9999}""")

        val result = initConfig(target, force = false)

        assertIs<InitConfigResult.Refused>(result)
        // User data is preserved untouched.
        assertEquals("""{"port": 9999}""", Files.readString(target))
    }

    @Test
    fun `init overwrites when force is set`(@TempDir dir: Path) {
        val target = dir.resolve("config.json")
        Files.writeString(target, """{"port": 9999}""")

        val result = initConfig(target, force = true)

        val written = assertIs<InitConfigResult.Written>(result)
        assertTrue(written.overwritten)
        // Old content is gone, replaced by the default seed (default port 8087).
        assertEquals(8087, AppConfig.load(target).port)
    }
}
