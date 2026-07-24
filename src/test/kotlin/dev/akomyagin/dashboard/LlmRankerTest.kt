package dev.akomyagin.dashboard

import dev.akomyagin.dashboard.rank.LlmRanker
import dev.akomyagin.dashboard.scan.TodoItem
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [LlmRanker] must stay usable offline: no key, a transport error, and a
 * malformed LLM payload all fall back to the heuristic ranker rather than
 * throwing — only a well-formed response is actually used.
 */
class LlmRankerTest {

    private fun items() = listOf(
        TodoItem(repo = "shelf", marker = "TODO", text = "tidy this up", file = "a.kt", line = 1),
        TodoItem(repo = "shelf", marker = "FIXME", text = "critical auth leak", file = "b.kt", line = 2),
    )

    private fun mockClient(handler: io.ktor.client.engine.mock.MockRequestHandler): HttpClient =
        HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) { json() }
        }

    @Test
    fun `no api key falls back to heuristic ranking without any network call`() = runTest {
        var called = false
        val ranker = LlmRanker(
            client = mockClient { called = true; respondError(HttpStatusCode.InternalServerError) },
            apiKey = null,
        )

        val ranked = ranker.rank(items())

        assertEquals(false, called)
        assertEquals("heuristic", ranked.first().reason)
        assertEquals("FIXME", ranked.first().marker) // heuristic also ranks FIXME+critical highest
    }

    @Test
    fun `well-formed llm response is parsed and applied`() = runTest {
        val body = """{"choices":[{"message":{"role":"assistant","content":"{\"items\":[{\"index\":0,\"priority\":2,\"reason\":\"minor cleanup\"},{\"index\":1,\"priority\":5,\"reason\":\"security critical\"}]}"}}]}"""
        val ranker = LlmRanker(
            client = mockClient {
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
            apiKey = "test-key",
        )

        val ranked = ranker.rank(items())

        assertEquals(2, ranked.size)
        assertEquals("FIXME", ranked[0].marker)
        assertEquals(5, ranked[0].priority)
        assertEquals("security critical", ranked[0].reason)
        assertEquals("TODO", ranked[1].marker)
        assertEquals(2, ranked[1].priority)
    }

    @Test
    fun `network failure falls back to heuristic ranking`() = runTest {
        val ranker = LlmRanker(
            client = mockClient { throw RuntimeException("boom") },
            apiKey = "test-key",
        )

        val ranked = ranker.rank(items())

        assertEquals("heuristic", ranked.first().reason)
    }

    @Test
    fun `malformed llm payload falls back to heuristic ranking`() = runTest {
        val ranker = LlmRanker(
            client = mockClient {
                respond(
                    """{"choices":[{"message":{"role":"assistant","content":"not json"}}]}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
            apiKey = "test-key",
        )

        val ranked = ranker.rank(items())

        assertEquals("heuristic", ranked.first().reason)
    }

    @Test
    fun `duplicate item indices in the llm response do not crash and still rank every item`() = runTest {
        val body = """{"choices":[{"message":{"role":"assistant","content":"{\"items\":[{\"index\":0,\"priority\":2,\"reason\":\"dup a\"},{\"index\":0,\"priority\":3,\"reason\":\"dup b\"}]}"}}]}"""
        val ranker = LlmRanker(
            client = mockClient {
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
            apiKey = "test-key",
        )

        val ranked = ranker.rank(items())

        // Item 1 never got a ranking (only index 0 was sent, twice) — it must still
        // come back rather than being dropped, with the documented "no ranking" marker.
        assertEquals(2, ranked.size)
        assertEquals(true, ranked.any { it.reason == "llm: no ranking returned for item" })
    }

    @Test
    fun `empty item list short-circuits without a network call`() = runTest {
        var called = false
        val ranker = LlmRanker(
            client = mockClient { called = true; respondError(HttpStatusCode.InternalServerError) },
            apiKey = "test-key",
        )

        val ranked = ranker.rank(emptyList())

        assertEquals(emptyList(), ranked)
        assertEquals(false, called)
    }
}
