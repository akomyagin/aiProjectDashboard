package dev.akomyagin.dashboard.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Outcome of a `--init-config` run, returned as data instead of printed directly
 * so the initialization logic stays a pure function of its inputs and can be
 * unit-tested against a temp directory without touching the user's real
 * ~/.config or capturing stdout. [Main] turns this into console output + exit code.
 */
sealed interface InitConfigResult {
    /** The config file was created (fresh install, or `--force` overwrite). */
    data class Written(val path: Path, val overwritten: Boolean) : InitConfigResult

    /** A file already exists and `--force` was not given — nothing was touched. */
    data class Refused(val path: Path) : InitConfigResult
}

/**
 * Materialize a starter config at [path]. We generate the content from the
 * built-in default portfolio (rather than shipping/reading a separate template
 * file) so the seed always matches the current [AppConfig] schema and there is
 * one source of truth for defaults.
 *
 * An existing file is never silently clobbered: without [force] we [Refused]
 * and leave the user's edits intact. Parent directories are created as needed
 * so a first run works on a clean machine.
 */
fun initConfig(path: Path, force: Boolean = false): InitConfigResult {
    val alreadyExists = path.exists()
    if (alreadyExists && !force) return InitConfigResult.Refused(path)

    path.parent?.let { Files.createDirectories(it) }
    Files.writeString(path, AppConfig.serialize(AppConfig.default()))
    return InitConfigResult.Written(path, overwritten = alreadyExists)
}
