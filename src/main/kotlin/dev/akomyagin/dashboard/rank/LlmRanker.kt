package dev.akomyagin.dashboard.rank

import dev.akomyagin.dashboard.scan.TodoItem
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * LLM-backed ranker (post-MVP): sends the whole scanned batch to an
 * OpenAI-compatible `/chat/completions` endpoint in a single request and asks
 * for structured `priority 1..5 + reason` JSON per item.
 *
 * BYOK: the key is read only from an environment variable, never from
 * config/repo. Missing key, network failure, or a malformed LLM response all
 * fall back to [HeuristicRanker] silently — the app must stay fully usable
 * offline (portfolio's offline-mode convention), so a broken LLM call must
 * never surface as an error to the user.
 */
class LlmRanker(
    private val client: HttpClient = defaultClient,
    private val apiKey: String? = System.getenv(API_KEY_ENV),
    private val baseUrl: String = System.getenv(BASE_URL_ENV) ?: "https://api.openai.com/v1",
    private val model: String = System.getenv(MODEL_ENV) ?: "gpt-4o-mini",
    private val fallback: TodoRanker = HeuristicRanker(),
) : TodoRanker {

    init {
        // A visible, one-time signal that this run is network-dependent — the project's
        // offline-by-default posture means silently flipping that on (e.g. via a stray
        // inherited env var) must not go unnoticed.
        if (apiKey != null) {
            System.err.println("LlmRanker: $API_KEY_ENV detected — TODO ranking will call $baseUrl (model=$model).")
        }
    }

    override suspend fun rank(items: List<TodoItem>): List<TodoItem> {
        if (items.isEmpty()) return items
        val key = apiKey ?: return fallback.rank(items)
        return runCatching { rankWithLlm(items, key) }.getOrElse { e ->
            System.err.println("LlmRanker: LLM ranking failed (${e.message}), falling back to heuristic ranker.")
            fallback.rank(items)
        }
    }

    private suspend fun rankWithLlm(items: List<TodoItem>, key: String): List<TodoItem> {
        val response: ChatCompletionResponse = client.post("$baseUrl/chat/completions") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $key")
            setBody(
                ChatCompletionRequest(
                    model = model,
                    messages = listOf(
                        ChatMessage("system", SYSTEM_PROMPT),
                        ChatMessage("user", buildPrompt(items)),
                    ),
                    responseFormat = ResponseFormat("json_object"),
                ),
            )
        }.body()

        val content = response.choices.firstOrNull()?.message?.content
            ?: error("empty LLM response: no choices")
        val parsed = jsonCodec.decodeFromString<LlmRankings>(content)
        val byIndex = parsed.items.associateBy { it.index }
        if (byIndex.size < parsed.items.size) {
            System.err.println(
                "LlmRanker: LLM response had duplicate item indices " +
                    "(${parsed.items.size} entries, ${byIndex.size} unique) — some rankings were discarded.",
            )
        }
        return items
            .mapIndexed { i, item ->
                val ranking = byIndex[i]
                    ?: return@mapIndexed item.copy(priority = 1, reason = "llm: no ranking returned for item")
                item.copy(priority = ranking.priority.coerceIn(1, 5), reason = ranking.reason)
            }
            .sortedByDescending { it.priority }
    }

    private fun buildPrompt(items: List<TodoItem>): String = buildString {
        appendLine("Rank each TODO item's urgency from 1 (low) to 5 (critical) and give a one-sentence reason.")
        appendLine("""Return strict JSON: {"items": [{"index": <int>, "priority": <1..5>, "reason": <string>}, ...]}""")
        items.forEachIndexed { i, item ->
            appendLine("$i. [${item.marker}] ${item.repo}/${item.file}:${item.line} — ${item.text}")
        }
    }

    companion object {
        const val API_KEY_ENV = "DASHBOARD_LLM_API_KEY"
        const val BASE_URL_ENV = "DASHBOARD_LLM_BASE_URL"
        const val MODEL_ENV = "DASHBOARD_LLM_MODEL"

        private const val SYSTEM_PROMPT =
            "You triage TODO/FIXME/HACK comments in a software portfolio. Respond with strict JSON only, no prose."

        private val jsonCodec = Json { ignoreUnknownKeys = true }

        private val defaultClient: HttpClient by lazy {
            HttpClient(CIO) {
                install(ContentNegotiation) { json(jsonCodec) }
                install(HttpTimeout) { requestTimeoutMillis = 20_000 }
            }
        }
    }
}

@Serializable
internal data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerialName("response_format") val responseFormat: ResponseFormat? = null,
)

@Serializable
internal data class ChatMessage(val role: String, val content: String)

@Serializable
internal data class ResponseFormat(val type: String)

@Serializable
internal data class ChatCompletionResponse(val choices: List<Choice>)

@Serializable
internal data class Choice(val message: ChatMessage)

@Serializable
internal data class LlmRankings(val items: List<LlmItemRanking>)

@Serializable
internal data class LlmItemRanking(val index: Int, val priority: Int, val reason: String)
