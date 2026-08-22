package dev.akomyagin.dashboard.cache

import dev.akomyagin.dashboard.github.CiStatus
import dev.akomyagin.dashboard.github.CommitInfo
import dev.akomyagin.dashboard.github.RepoStatus
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatusCacheTest {

    private fun ok(name: String, ci: CiStatus = CiStatus.SUCCESS) = RepoStatus(
        name = name,
        fullName = "akomyagin/$name",
        ci = ci,
        ciDetail = "CI",
        lastCommit = CommitInfo("abc1234", "do the thing", "alkom", "2026-07-01T10:00:00Z"),
        openPrs = 1,
    )

    private fun failed(name: String, error: String) =
        RepoStatus(name = name, fullName = "akomyagin/$name", error = error)

    // --- StatusCache persistence -------------------------------------------------

    @Test
    fun `load without a file returns an empty map`() {
        val dir = Files.createTempDirectory("status-cache-test")
        val cache = StatusCache(dir.resolve("nope.json"))
        assertTrue(cache.load().isEmpty())
    }

    @Test
    fun `save then load round-trips the entries`() {
        val dir = Files.createTempDirectory("status-cache-test")
        val path = dir.resolve("status-cache.json")
        val cache = StatusCache(path)

        val entries = mapOf("akomyagin/shelf" to ok("shelf"))
        cache.save(entries)

        assertTrue(Files.exists(path))
        val loaded = cache.load()
        assertEquals(entries, loaded)
        assertEquals(CiStatus.SUCCESS, loaded["akomyagin/shelf"]!!.ci)
    }

    @Test
    fun `save creates missing parent directories`() {
        val dir = Files.createTempDirectory("status-cache-test")
        val path = dir.resolve("nested/deeper/status-cache.json")
        val cache = StatusCache(path)

        cache.save(mapOf("akomyagin/gitl" to ok("gitl")))

        assertTrue(Files.exists(path))
        assertEquals(1, cache.load().size)
    }

    @Test
    fun `corrupt json degrades to an empty map instead of throwing`() {
        val dir = Files.createTempDirectory("status-cache-test")
        val path = dir.resolve("status-cache.json")
        path.writeText("{ this is not valid json ]]")

        val cache = StatusCache(path)
        assertTrue(cache.load().isEmpty())
    }

    // --- mergeWithCache branches -------------------------------------------------

    @Test
    fun `live success with no prior cache passes through and populates the cache`() {
        val fresh = listOf(ok("shelf"))
        val (display, updated) = mergeWithCache(fresh, emptyMap())

        val s = display.single()
        assertFalse(s.stale)
        assertFalse(s.ciChanged)
        assertEquals(ok("shelf"), updated["akomyagin/shelf"])
    }

    @Test
    fun `live success with matching cached CI does not flag ciChanged`() {
        val cached = mapOf("akomyagin/shelf" to ok("shelf", CiStatus.SUCCESS))
        val (display, updated) = mergeWithCache(listOf(ok("shelf", CiStatus.SUCCESS)), cached)

        assertFalse(display.single().ciChanged)
        assertEquals(ok("shelf", CiStatus.SUCCESS), updated["akomyagin/shelf"])
    }

    @Test
    fun `live success with a differing cached CI flags ciChanged and updates the cache`() {
        val cached = mapOf("akomyagin/shelf" to ok("shelf", CiStatus.SUCCESS))
        val (display, updated) = mergeWithCache(listOf(ok("shelf", CiStatus.FAILURE)), cached)

        val s = display.single()
        assertTrue(s.ciChanged)
        assertFalse(s.stale)
        assertEquals(CiStatus.FAILURE, s.ci)
        // The clean snapshot (no ciChanged flag) is what gets stored.
        val stored = updated["akomyagin/shelf"]!!
        assertEquals(CiStatus.FAILURE, stored.ci)
        assertFalse(stored.ciChanged)
    }

    @Test
    fun `live failure with a cache hit returns the cached snapshot flagged stale with the live error`() {
        val cached = mapOf("akomyagin/shelf" to ok("shelf", CiStatus.SUCCESS))
        val (display, updated) = mergeWithCache(
            listOf(failed("shelf", "gh failed: network down")),
            cached,
        )

        val s = display.single()
        assertTrue(s.stale)
        assertEquals("gh failed: network down", s.error) // live error preserved, not null
        assertEquals(CiStatus.SUCCESS, s.ci) // CI from the cached snapshot
        assertEquals("abc1234", s.lastCommit?.sha) // cached commit shown
        // A degraded poll never overwrites a good cache entry.
        assertEquals(ok("shelf", CiStatus.SUCCESS), updated["akomyagin/shelf"])
    }

    @Test
    fun `live failure with no cache passes through unchanged and stays not stale`() {
        val (display, updated) = mergeWithCache(
            listOf(failed("shelf", "gh not authenticated")),
            emptyMap(),
        )

        val s = display.single()
        assertFalse(s.stale)
        assertEquals("gh not authenticated", s.error)
        // Nothing good to cache.
        assertTrue(updated.isEmpty())
    }
}
