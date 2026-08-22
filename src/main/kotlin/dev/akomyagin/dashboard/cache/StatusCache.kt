package dev.akomyagin.dashboard.cache

import dev.akomyagin.dashboard.github.RepoStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists

/**
 * Last-known-good status per repo, keyed by [RepoStatus.fullName], persisted as
 * one JSON file. Not a history/time-series — one entry per repo, always
 * overwritten on the next successful poll. Read/write failures degrade to "no
 * cache" silently; this is a convenience layer, never a source of truth that
 * can crash a poll.
 */
class StatusCache(private val path: Path) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): Map<String, RepoStatus> = runCatching {
        if (!path.exists()) return emptyMap()
        json.decodeFromString<Map<String, RepoStatus>>(Files.readString(path))
    }.getOrElse { emptyMap() }

    /**
     * Write via a temp file + atomic rename in the same directory, so a
     * concurrent reader/writer (e.g. the startup diagnostics thread and the
     * first `/api/status` request racing at web-server startup) never observes
     * a partially-written or corrupted file.
     */
    fun save(entries: Map<String, RepoStatus>) {
        runCatching {
            val dir = path.parent ?: Path.of(".")
            Files.createDirectories(dir)
            val tmp = Files.createTempFile(dir, "status-cache", ".tmp")
            Files.writeString(tmp, json.encodeToString(entries))
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}

/**
 * Merge a fresh live poll with the last-known-good cache. Pure and side-effect
 * free so it can be unit-tested without coroutines/network/disk.
 *
 * Rules, per repo keyed by [RepoStatus.fullName]:
 *  - live success (`error == null`): if the previous cached CI differs from the
 *    live CI, flag `ciChanged = true`; the "clean" live snapshot (without
 *    stale/ciChanged flags) replaces the cache entry.
 *  - live failure (`error != null`) with a cache hit: return the cached
 *    status copied with `stale = true` and the **live** `error` preserved, so
 *    diagnostics still see the real problem while showing the last snapshot.
 *    The cache is NOT overwritten by a degraded response.
 *  - live failure with no cache hit: pass through unchanged (`stale = false`).
 *
 * Returns the display list plus the updated cache map to persist.
 */
fun mergeWithCache(
    fresh: List<RepoStatus>,
    cached: Map<String, RepoStatus>,
): Pair<List<RepoStatus>, Map<String, RepoStatus>> {
    val updatedCache = cached.toMutableMap()
    val display = fresh.map { live ->
        val key = live.fullName
        val prior = cached[key]
        if (live.error == null) {
            // Cache a clean snapshot (no stale/ciChanged flags leak into storage).
            updatedCache[key] = live
            val ciChanged = prior != null && prior.error == null && prior.ci != live.ci
            if (ciChanged) live.copy(ciChanged = true) else live
        } else {
            // Degraded live poll: fall back to the archived snapshot if we have one,
            // keeping the live error so the degradation stays visible.
            if (prior != null) {
                prior.copy(error = live.error, stale = true)
            } else {
                live
            }
        }
    }
    return display to updatedCache
}
