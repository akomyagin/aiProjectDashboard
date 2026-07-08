package dev.akomyagin.dashboard.config

import java.nio.file.Path
import kotlin.io.path.Path

/** Expand a leading `~` to the user's home directory. */
fun expandHome(raw: String): Path {
    val home = System.getProperty("user.home")
    return when {
        raw == "~" -> Path(home)
        raw.startsWith("~/") -> Path(home, raw.substring(2))
        else -> Path(raw)
    }
}
