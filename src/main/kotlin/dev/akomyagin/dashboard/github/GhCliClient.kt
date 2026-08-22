package dev.akomyagin.dashboard.github

import dev.akomyagin.dashboard.config.RepoConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeUnit

/**
 * [GitHubClient] backed by the `gh` CLI. Using `gh` means we reuse the user's
 * existing `gh auth` session — no personal token has to be stored by this app
 * (see the "no secrets in shipped artifacts" principle). Status for the whole
 * portfolio is fetched via one `gh api graphql` call per (chunk of) repos
 * instead of 3 REST-ish `gh` subprocesses per repo (Post-MVP: GraphQL batching
 * — see `docs/POST_MVP_PLAN.md` §2) — fewer network round-trips and less
 * rate-limit exposure as the portfolio grows.
 *
 * If `gh` is missing, unauthenticated, or a call fails/times out, the failure is
 * captured in [RepoStatus.error] and the dashboard renders that repo in a
 * degraded state instead of failing the whole portfolio. A batch call failure
 * retries each repo individually before giving up, so a single flaky repo still
 * never takes down the rest of the chunk (see [fetchStatusesBatch]).
 */
class GhCliClient(
    private val ghPath: String = "gh",
    private val timeoutSeconds: Long = 20,
) : GitHubClient {

    override suspend fun fetchStatus(repo: RepoConfig): RepoStatus = fetchStatuses(listOf(repo)).single()

    override suspend fun fetchStatuses(repos: List<RepoConfig>): List<RepoStatus> = withContext(Dispatchers.IO) {
        if (repos.isEmpty()) return@withContext emptyList()
        repos.chunked(BATCH_SIZE)
            .map { chunk -> async { fetchStatusesBatch(chunk) } }
            .awaitAll()
            .flatten()
    }

    /**
     * One `gh api graphql` call for [repos]. Both the subprocess call and the
     * response parsing are covered by the same catch: an unreachable `gh`, a
     * timeout, or an unexpectedly-shaped/malformed response all land here.
     *
     * A single failed batch call must not degrade every repo in it identically —
     * that would violate "one flaky repo never takes down the rest" the moment a
     * chunk holds more than one repo (e.g. a transient timeout on the shared HTTP
     * round-trip). So on failure (for a chunk of more than one repo) each repo is
     * retried as its own single-repo GraphQL call, restoring per-repo isolation;
     * only a repo whose *individual* retry also fails gets degraded.
     */
    private fun fetchStatusesBatch(repos: List<RepoConfig>): List<RepoStatus> = try {
        val out = runGh("api", "graphql", "-f", "query=${buildQuery(repos)}")
        parseGraphQlResponse(repos, out)
    } catch (e: Exception) {
        if (repos.size <= 1) {
            repos.map { degraded(it, e.message ?: e::class.simpleName ?: "github lookup failed") }
        } else {
            repos.map { fetchStatusesBatch(listOf(it)).single() }
        }
    }

    private fun degraded(repo: RepoConfig, error: String) =
        RepoStatus(name = repo.name, fullName = repo.fullName, error = error)

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

    /** Expected, recoverable `gh` failure → degrade a single repo (or a whole chunk), not the portfolio. */
    private class GhException(message: String) : RuntimeException(message)

    companion object {
        /** Repos per `gh api graphql` call — comfortably under GraphQL node/complexity limits. */
        private const val BATCH_SIZE = 50

        /** How many recent check suites on the default branch's HEAD commit to aggregate CI status from. */
        private const val CHECK_SUITES_LOOKBACK = 5

        private val json = Json { ignoreUnknownKeys = true }

        /** Map GitHub's (status, conclusion) pair to our normalized [CiStatus]. */
        fun mapCi(status: String?, conclusion: String?): CiStatus = when {
            status != null && status != "completed" -> CiStatus.PENDING
            conclusion == "success" -> CiStatus.SUCCESS
            conclusion in setOf("failure", "cancelled", "timed_out", "startup_failure", "action_required") ->
                CiStatus.FAILURE
            else -> CiStatus.UNKNOWN
        }

        /**
         * Builds one GraphQL query aliasing every repo as `r0`, `r1`, ... so a whole
         * chunk resolves in a single request. CI comes from the check suites attached
         * to the default branch's HEAD commit (there is no single "latest Actions run"
         * concept in the GraphQL schema the way `gh run list` exposes one over REST).
         * Public for unit testing without shelling out to `gh`.
         */
        fun buildQuery(repos: List<RepoConfig>): String = buildString {
            appendLine("query {")
            repos.forEachIndexed { i, repo ->
                appendLine("  r$i: repository(owner: ${quote(repo.owner)}, name: ${quote(repo.name)}) {")
                appendLine("    pullRequests(states: OPEN, first: 1, orderBy: {field: CREATED_AT, direction: ASC}) {")
                appendLine("      totalCount")
                appendLine("      nodes { number title createdAt url }")
                appendLine("    }")
                appendLine("    defaultBranchRef {")
                appendLine("      target {")
                appendLine("        ... on Commit {")
                appendLine("          oid")
                appendLine("          messageHeadline")
                appendLine("          committedDate")
                appendLine("          author { name }")
                appendLine("          checkSuites(last: $CHECK_SUITES_LOOKBACK) {")
                appendLine("            nodes { status conclusion workflowRun { workflow { name } } }")
                appendLine("          }")
                appendLine("        }")
                appendLine("      }")
                appendLine("    }")
                appendLine("  }")
            }
            append("}")
        }

        /** GraphQL string-literal escaping is JSON-compatible — reuse the JSON encoder
         *  rather than hand-rolling it, so newlines/control characters (not just `\`
         *  and `"`) can never produce an invalid query document. */
        private fun quote(value: String): String = json.encodeToString(value)

        /**
         * Parses one `gh api graphql` response into [RepoStatus] entries, in [repos]
         * order. A repo whose alias is missing from `data` (GraphQL returns `null`
         * plus a matching entry in the top-level `errors` array — e.g. renamed/private/
         * deleted repo) degrades individually rather than failing the whole batch.
         * Public for unit testing without shelling out to `gh`.
         */
        fun parseGraphQlResponse(repos: List<RepoConfig>, responseJson: String): List<RepoStatus> {
            val root = json.parseToJsonElement(responseJson).jsonObject
            val data = root["data"].obj()
            val errors = root["errors"].arr()?.mapNotNull { it.obj() } ?: emptyList()
            val failedAliases = errors
                .mapNotNull { it["path"].arr()?.firstOrNull()?.jsonPrimitive?.contentOrNull }
                .toSet()
            // Document-level/request-wide errors (rate limit, permissions, malformed
            // query) carry no `path` at all — surface the actual message instead of a
            // generic one for any repo that's missing data but wasn't individually
            // named, so the cause isn't lost.
            val unpathedMessage = errors.firstOrNull { it["path"].arr() == null }
                ?.get("message")?.jsonPrimitive?.contentOrNull

            return repos.mapIndexed { i, repo ->
                val alias = "r$i"
                val node = data?.get(alias).obj()
                when {
                    node != null -> parseRepoNode(repo, node)
                    alias in failedAliases ->
                        RepoStatus(name = repo.name, fullName = repo.fullName, error = "repository not found or inaccessible")
                    unpathedMessage != null ->
                        RepoStatus(name = repo.name, fullName = repo.fullName, error = "graphql error: $unpathedMessage")
                    else ->
                        RepoStatus(name = repo.name, fullName = repo.fullName, error = "no data returned for repository")
                }
            }
        }

        private fun parseRepoNode(repo: RepoConfig, node: JsonObject): RepoStatus {
            val prBlock = node["pullRequests"].obj()
            val openPrs = prBlock?.get("totalCount")?.jsonPrimitive?.intOrNull ?: 0
            val oldestOpenPr = prBlock?.get("nodes").arr()?.firstOrNull()?.obj()?.toPrInfo()
            val target = node["defaultBranchRef"].obj()?.get("target").obj()
                ?: return RepoStatus(
                    name = repo.name,
                    fullName = repo.fullName,
                    ciDetail = "no commits",
                    openPrs = openPrs,
                    oldestOpenPr = oldestOpenPr,
                )

            val commit = CommitInfo(
                sha = target["oid"]?.jsonPrimitive?.contentOrNull?.take(7) ?: "",
                message = target["messageHeadline"]?.jsonPrimitive?.contentOrNull ?: "",
                author = target["author"].obj()?.get("name")?.jsonPrimitive?.contentOrNull ?: "unknown",
                date = target["committedDate"]?.jsonPrimitive?.contentOrNull ?: "",
            )
            val checkNodes = target["checkSuites"].obj()?.get("nodes").arr()
                ?.mapNotNull { it as? JsonObject }
                ?: emptyList()
            val (ci, ciDetail) = aggregateCi(checkNodes)

            return RepoStatus(
                name = repo.name,
                fullName = repo.fullName,
                ci = ci,
                ciDetail = ciDetail,
                lastCommit = commit,
                openPrs = openPrs,
                oldestOpenPr = oldestOpenPr,
            )
        }

        /** Maps one `pullRequests.nodes[0]` entry to [PrInfo]; missing `number` makes the node unusable. */
        private fun JsonObject.toPrInfo(): PrInfo? {
            val number = this["number"]?.jsonPrimitive?.intOrNull ?: return null
            return PrInfo(
                number = number,
                title = this["title"]?.jsonPrimitive?.contentOrNull ?: "",
                createdAt = this["createdAt"]?.jsonPrimitive?.contentOrNull ?: "",
                url = this["url"]?.jsonPrimitive?.contentOrNull ?: "",
            )
        }

        /**
         * A commit can carry several check suites (one per workflow), plus GitHub
         * sometimes attaches a placeholder check suite — `status: QUEUED`,
         * `conclusion: null`, `workflowRun: null` — that was never claimed by an
         * actual Actions run (observed live against real repos, not just a
         * hypothetical). That exact shape carries no CI signal and must be dropped,
         * or a phantom "pending" would shadow a real SUCCESS/FAILURE from the
         * commit's actual workflow run. Filtering is deliberately narrow (that exact
         * status+conclusion+workflowRun combination) rather than "no workflowRun" —
         * `workflowRun` is an Actions-specific field that's null by schema design for
         * any *real* check suite created by a non-Actions integration (third-party CI
         * via the Checks API), and those must still count. Among what remains,
         * precedence FAILURE > PENDING > SUCCESS > UNKNOWN — a broken build must
         * never be hidden behind an unrelated passing check on the same commit.
         */
        private fun aggregateCi(nodes: List<JsonObject>): Pair<CiStatus, String?> {
            val real = nodes.filterNot { it.isPhantomCheckSuite() }
            if (real.isEmpty()) return CiStatus.UNKNOWN to "no runs"
            val mapped = real.map { node ->
                val status = node["status"]?.jsonPrimitive?.contentOrNull?.lowercase()
                val conclusion = node["conclusion"]?.jsonPrimitive?.contentOrNull?.lowercase()
                val name = node["workflowRun"].obj()
                    ?.get("workflow").obj()
                    ?.get("name")?.jsonPrimitive?.contentOrNull
                mapCi(status, conclusion) to name
            }
            return listOf(CiStatus.FAILURE, CiStatus.PENDING, CiStatus.SUCCESS, CiStatus.UNKNOWN)
                .firstNotNullOfOrNull { want -> mapped.firstOrNull { it.first == want } }
                ?: (CiStatus.UNKNOWN to null)
        }

        /** The exact GitHub placeholder shape: queued, no conclusion, no workflow run attached. */
        private fun JsonObject.isPhantomCheckSuite(): Boolean =
            this["status"]?.jsonPrimitive?.contentOrNull == "QUEUED" &&
                this["conclusion"]?.jsonPrimitive?.contentOrNull == null &&
                this["workflowRun"].obj() == null

        /** JSON `null` is a distinct [JsonElement] (not Kotlin `null`) — `.jsonObject`/`.jsonArray` throw on it, so
         *  navigate optional fields with these instead of the throwing extension properties. */
        private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
        private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
    }
}
