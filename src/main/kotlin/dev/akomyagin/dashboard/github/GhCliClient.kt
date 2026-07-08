package dev.akomyagin.dashboard.github

import dev.akomyagin.dashboard.config.RepoConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeUnit

/**
 * Stage-1 [GitHubClient] backed by the `gh` CLI. Using `gh` means we reuse the
 * user's existing `gh auth` session — no personal token has to be stored by this
 * app (see the "no secrets in shipped artifacts" principle). Every call is a
 * short-lived subprocess; results are parsed from `gh`'s JSON output.
 *
 * If `gh` is missing, unauthenticated, or a call fails/times out, the failure is
 * captured in [RepoStatus.error] and the dashboard renders that one repo in a
 * degraded state instead of failing the whole portfolio. A single flaky repo must
 * never take down the page.
 */
class GhCliClient(
    private val ghPath: String = "gh",
    private val timeoutSeconds: Long = 20,
) : GitHubClient {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetchStatus(repo: RepoConfig): RepoStatus = withContext(Dispatchers.IO) {
        try {
            val (ciStatus, ciDetail) = fetchCi(repo)
            val commit = fetchLastCommit(repo)
            val prs = fetchOpenPrCount(repo)
            RepoStatus(
                name = repo.name,
                fullName = repo.fullName,
                ci = ciStatus,
                ciDetail = ciDetail,
                lastCommit = commit,
                openPrs = prs,
            )
        } catch (e: GhException) {
            // Expected, per-repo failure (repo missing, gh not authed, timeout).
            RepoStatus(name = repo.name, fullName = repo.fullName, error = e.message ?: "github lookup failed")
        } catch (e: Exception) {
            // Unexpected (e.g. gh binary not on PATH) — still degrade this repo only.
            RepoStatus(
                name = repo.name,
                fullName = repo.fullName,
                error = e.message ?: e::class.simpleName ?: "github lookup failed",
            )
        }
    }

    /** Latest Actions run: normalized [CiStatus] plus the workflow name as detail. */
    private fun fetchCi(repo: RepoConfig): Pair<CiStatus, String?> {
        val out = runGh(
            "run", "list", "--repo", repo.fullName, "--limit", "1",
            "--json", "status,conclusion,name",
        )
        val arr = parseArray(out)
        if (arr.isEmpty()) return CiStatus.UNKNOWN to "no runs"
        val run = arr[0].jsonObject
        val status = run["status"]?.jsonPrimitive?.content
        val conclusion = run["conclusion"]?.jsonPrimitive?.content
        val name = run["name"]?.jsonPrimitive?.content
        return mapCi(status, conclusion) to name
    }

    /** Most recent commit on the default branch (hash + subject + author + date). */
    private fun fetchLastCommit(repo: RepoConfig): CommitInfo? {
        val out = runGh("api", "repos/${repo.fullName}/commits?per_page=1")
        val arr = parseArray(out)
        if (arr.isEmpty()) return null
        val c = arr[0].jsonObject
        val commit = c["commit"]?.jsonObject ?: return null
        val author = commit["author"]?.jsonObject
        return CommitInfo(
            sha = c["sha"]?.jsonPrimitive?.content?.take(7) ?: "",
            message = commit["message"]?.jsonPrimitive?.content?.substringBefore('\n') ?: "",
            author = author?.get("name")?.jsonPrimitive?.content ?: "unknown",
            date = author?.get("date")?.jsonPrimitive?.content ?: "",
        )
    }

    /** Number of open pull requests. */
    private fun fetchOpenPrCount(repo: RepoConfig): Int {
        val out = runGh("pr", "list", "--repo", repo.fullName, "--state", "open", "--json", "number")
        return parseArray(out).size
    }

    private fun parseArray(out: String) =
        try {
            json.parseToJsonElement(out).jsonArray
        } catch (e: Exception) {
            throw GhException("could not parse gh output: ${e.message}")
        }

    /**
     * Run `gh` with args and return stdout. Throws [GhException] on non-zero exit
     * or timeout so the caller can degrade that repo. Both stdout and stderr are
     * drained on background threads while the process runs — reading them only
     * after `waitFor` can deadlock if the child fills a pipe buffer.
     */
    private fun runGh(vararg args: String): String {
        val proc = try {
            ProcessBuilder(listOf(ghPath) + args)
                .redirectErrorStream(false)
                .start()
        } catch (e: Exception) {
            // gh binary not found / not executable — report readably.
            throw GhException("cannot run '$ghPath': ${e.message}")
        }

        val stdoutReader = drainAsync(proc.inputStream)
        val stderrReader = drainAsync(proc.errorStream)

        val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            stdoutReader.join()
            stderrReader.join()
            throw GhException("gh timed out after ${timeoutSeconds}s: ${args.joinToString(" ")}")
        }

        val stdout = stdoutReader.await()
        val stderr = stderrReader.await()
        if (proc.exitValue() != 0) {
            val detail = stderr.trim().ifEmpty { "unknown error (exit ${proc.exitValue()})" }
            throw GhException("gh failed: ${detail.substringBefore('\n')}")
        }
        return stdout
    }

    /** Read a stream fully on a daemon thread; results retrievable via [StreamReader.await]. */
    private fun drainAsync(stream: java.io.InputStream): StreamReader {
        val reader = StreamReader(stream)
        reader.isDaemon = true
        reader.start()
        return reader
    }

    private class StreamReader(private val stream: java.io.InputStream) : Thread() {
        @Volatile
        private var result: String = ""

        override fun run() {
            result = try {
                stream.readBytes().decodeToString()
            } catch (_: Exception) {
                ""
            }
        }

        fun await(): String {
            join()
            return result
        }
    }

    /** Expected, recoverable `gh` failure → degrade a single repo, not the portfolio. */
    private class GhException(message: String) : RuntimeException(message)

    companion object {
        /** Map GitHub's (status, conclusion) pair to our normalized [CiStatus]. */
        fun mapCi(status: String?, conclusion: String?): CiStatus = when {
            status != null && status != "completed" -> CiStatus.PENDING
            conclusion == "success" -> CiStatus.SUCCESS
            conclusion in setOf("failure", "cancelled", "timed_out", "startup_failure", "action_required") ->
                CiStatus.FAILURE
            else -> CiStatus.UNKNOWN
        }
    }
}
