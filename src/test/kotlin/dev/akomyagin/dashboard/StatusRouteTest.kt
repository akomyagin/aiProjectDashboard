package dev.akomyagin.dashboard

import dev.akomyagin.dashboard.config.AppConfig
import dev.akomyagin.dashboard.config.RepoConfig
import dev.akomyagin.dashboard.github.CiStatus
import dev.akomyagin.dashboard.http.installPlugins
import dev.akomyagin.dashboard.http.routes
import dev.akomyagin.dashboard.rank.HeuristicRanker
import dev.akomyagin.dashboard.scan.TodoScanner
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Route-level test through `ktor-server-test-host`: wires the real Ktor plugins
 * and routing to a fake [dev.akomyagin.dashboard.github.GitHubClient], then asserts
 * the JSON contract of `/api/status`, including the degraded-repo shape.
 */
class StatusRouteTest {

    private fun service(): DashboardService {
        val cfg = AppConfig(repos = listOf(RepoConfig("shelf"), RepoConfig("gitl")))
        val github = FakeGitHubClient(
            byName = mapOf(
                "shelf" to FakeGitHubClient.ok("shelf", prs = 3, ci = CiStatus.SUCCESS),
                "gitl" to FakeGitHubClient.failed("gitl", "gh failed: not found"),
            ),
        )
        return DashboardService(cfg, github, HeuristicRanker(), TodoScanner(cfg))
    }

    @Test
    fun `GET api status returns the portfolio json with a degraded repo`() = testApplication {
        application {
            installPlugins()
            routes(service())
        }

        val resp = client.get("/api/status")
        assertEquals(HttpStatusCode.OK, resp.status)

        val arr = Json.parseToJsonElement(resp.bodyAsText()).jsonArray
        assertEquals(2, arr.size)

        val shelf = arr.map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == "shelf" }
        assertEquals("SUCCESS", shelf["ci"]!!.jsonPrimitive.content)
        assertEquals(3, shelf["openPrs"]!!.jsonPrimitive.content.toInt())
        // A healthy repo carries no error (key omitted or explicit null).
        assertTrue(shelf["error"].let { it == null || it.toString() == "null" })

        val gitl = arr.map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == "gitl" }
        assertEquals("gh failed: not found", gitl["error"]!!.jsonPrimitive.content)
        // `ci` defaults to UNKNOWN, which the server's Json omits — absent key == UNKNOWN.
        val gitlCi = gitl["ci"]?.jsonPrimitive?.content ?: "UNKNOWN"
        assertEquals("UNKNOWN", gitlCi)
    }

    @Test
    fun `GET api health returns ok`() = testApplication {
        application {
            installPlugins()
            routes(service())
        }
        val resp = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("ok"))
    }

    @Test
    fun `GET api todos returns ranked items scanned from disk`() = testApplication {
        val dir = Files.createTempDirectory("todos-route-test")
        dir.resolve("a.kt").writeText(
            """
            fun x() {
                // TODO: tidy this up
                // FIXME critical security leak in auth
            }
            """.trimIndent(),
        )
        val cfg = AppConfig(repos = listOf(RepoConfig(name = "sample", localPath = dir.toString())))
        val service = DashboardService(cfg, FakeGitHubClient(), HeuristicRanker(), TodoScanner(cfg))

        application {
            installPlugins()
            routes(service)
        }

        val resp = client.get("/api/todos")
        assertEquals(HttpStatusCode.OK, resp.status)

        val arr = Json.parseToJsonElement(resp.bodyAsText()).jsonArray
        assertEquals(2, arr.size)

        // HeuristicRanker sorts descending by priority; FIXME+"security"+"critical" outranks a bare TODO.
        val top = arr[0].jsonObject
        assertEquals("FIXME", top["marker"]!!.jsonPrimitive.content)
        assertEquals("sample", top["repo"]!!.jsonPrimitive.content)
        assertEquals("a.kt", top["file"]!!.jsonPrimitive.content)
        assertTrue(top["priority"]!!.jsonPrimitive.content.toInt() >= arr[1].jsonObject["priority"]!!.jsonPrimitive.content.toInt())
    }
}
