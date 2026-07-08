package dev.akomyagin.dashboard

import dev.akomyagin.dashboard.config.AppConfig
import dev.akomyagin.dashboard.github.CiStatus
import dev.akomyagin.dashboard.github.GhCliClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigAndCiTest {

    @Test
    fun `default config seeds the known portfolio`() {
        val cfg = AppConfig.default()
        assertTrue(cfg.repos.any { it.name == "aiProjectDashboard" })
        assertTrue(cfg.repos.all { it.owner == "akomyagin" })
        assertTrue(cfg.repos.all { it.localPath != null })
    }

    @Test
    fun `config round-trips through json`() {
        val cfg = AppConfig.default()
        val restored = AppConfig.load(writeTemp(AppConfig.serialize(cfg)))
        assertEquals(cfg.repos.size, restored.repos.size)
        assertEquals(cfg.markers, restored.markers)
    }

    @Test
    fun `ci mapping normalizes github vocabulary`() {
        assertEquals(CiStatus.SUCCESS, GhCliClient.mapCi("completed", "success"))
        assertEquals(CiStatus.FAILURE, GhCliClient.mapCi("completed", "failure"))
        assertEquals(CiStatus.FAILURE, GhCliClient.mapCi("completed", "timed_out"))
        assertEquals(CiStatus.PENDING, GhCliClient.mapCi("in_progress", null))
        assertEquals(CiStatus.UNKNOWN, GhCliClient.mapCi("completed", null))
    }

    private fun writeTemp(content: String): java.nio.file.Path {
        val f = java.nio.file.Files.createTempFile("cfg", ".json")
        java.nio.file.Files.writeString(f, content)
        return f
    }
}
