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
 * If `gh` is unavailable or a call fails, [RepoStatus.error] is populated and the
 * dashboard renders that repo in a degraded state instead of failing wholesale.
 */
class GhCliClient(
    private val ghPath: String = "gh",
    private val timeoutSeconds: Long = 20,
) : GitHubClient {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetchStatus(repo: RepoConfig): RepoStatus = withContext(Dispatchers.IO) {
        try {
            val ci = fetchCi(repo)
            val commit = fetchLastCommit(repo)
            val prs = fetchOpenPrCount(repo)
            RepoStatus(
                name = repo.name,
                fullName = repo.fullName,
                ci = ci.first,
                ciDetail = ci.second,
                lastCommit = commit,
                openPrs = prs,
            )
        } catch (e: Exception) {
            RepoStatus(name = repo.name, fullName = repo.fullName, error = e.message ?: "github lookup failed")
        }
    }

    /** Latest Actions run conclusion for the repo. */
    private fun fetchCi(repo: RepoConfig): Pair<CiStatus, String?> {
        val out = runGh(
            "run", "list", "--repo", repo.fullName, "--limit", "1",
            "--json", "status,conclusion,name",
        ) ?: return CiStatus.UNKNOWN to "no gh output"
        val arr = json.parseToJsonElement(out).jsonArray
        if (arr.isEmpty()) return CiStatus.UNKNOWN to "no runs"
        val run = arr[0].jsonObject
        val status = run["status"]?.jsonPrimitive?.content
        val conclusion = run["conclusion"]?.jsonPrimitive?.content
        val name = run["name"]?.jsonPrimitive?.content
        return mapCi(status, conclusion) to name
    }

    private fun fetchLastCommit(repo: RepoConfig): CommitInfo? {
        val out = runGh(
            "api", "repos/${repo.fullName}/commits?per_page=1",
        ) ?: return null
        val arr = json.parseToJsonElement(out).jsonArray
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

    private fun fetchOpenPrCount(repo: RepoConfig): Int {
        val out = runGh(
            "pr", "list", "--repo", repo.fullName, "--state", "open", "--json", "number",
        ) ?: return 0
        return runCatching { json.parseToJsonElement(out).jsonArray.size }.getOrDefault(0)
    }

    /** Run `gh` with args, returning stdout, or null on non-zero exit / timeout. */
    private fun runGh(vararg args: String): String? {
        val proc = ProcessBuilder(listOf(ghPath) + args)
            .redirectErrorStream(false)
            .start()
        if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            throw RuntimeException("gh timed out after ${timeoutSeconds}s: ${args.joinToString(" ")}")
        }
        val stdout = proc.inputStream.readBytes().decodeToString()
        if (proc.exitValue() != 0) {
            val stderr = proc.errorStream.readBytes().decodeToString().trim()
            throw RuntimeException("gh failed (${proc.exitValue()}): ${stderr.ifEmpty { "unknown error" }}")
        }
        return stdout
    }

    companion object {
        /** Map GitHub's (status, conclusion) pair to our normalized [CiStatus]. */
        fun mapCi(status: String?, conclusion: String?): CiStatus = when {
            status != null && status != "completed" -> CiStatus.PENDING
            conclusion == "success" -> CiStatus.SUCCESS
            conclusion in setOf("failure", "cancelled", "timed_out", "startup_failure") -> CiStatus.FAILURE
            conclusion == null -> CiStatus.UNKNOWN
            else -> CiStatus.UNKNOWN
        }
    }
}
