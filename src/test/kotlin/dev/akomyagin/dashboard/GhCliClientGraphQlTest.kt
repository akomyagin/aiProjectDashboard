package dev.akomyagin.dashboard

import dev.akomyagin.dashboard.config.RepoConfig
import dev.akomyagin.dashboard.github.CiStatus
import dev.akomyagin.dashboard.github.GhCliClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure unit tests for the GraphQL batching added to [GhCliClient] (Post-MVP:
 * one `gh api graphql` call per chunk instead of 3 REST-ish `gh` subprocesses
 * per repo — `docs/POST_MVP_PLAN.md` §2). [GhCliClient.buildQuery] and
 * [GhCliClient.parseGraphQlResponse] are pure and public specifically so this
 * can be tested without shelling out to `gh`.
 */
class GhCliClientGraphQlTest {

    private fun repos(vararg names: String) = names.map { RepoConfig(name = it) }

    @Test
    fun `buildQuery aliases every repo and quotes owner and name`() {
        val query = GhCliClient.buildQuery(repos("shelf", "gitl"))

        assertTrue(query.contains("r0: repository(owner: \"akomyagin\", name: \"shelf\")"))
        assertTrue(query.contains("r1: repository(owner: \"akomyagin\", name: \"gitl\")"))
        assertTrue(query.contains("pullRequests(states: OPEN)"))
        assertTrue(query.contains("checkSuites(last: 5)"))
    }

    @Test
    fun `parses a healthy repo with a single successful check suite`() {
        val body = """
            {"data":{"r0":{
                "pullRequests":{"totalCount":3},
                "defaultBranchRef":{"target":{
                    "oid":"abcdef1234567890",
                    "messageHeadline":"do the thing",
                    "committedDate":"2026-07-01T10:00:00Z",
                    "author":{"name":"alkom"},
                    "checkSuites":{"nodes":[
                        {"status":"COMPLETED","conclusion":"SUCCESS","workflowRun":{"workflow":{"name":"CI"}}}
                    ]}
                }}
            }}}
        """.trimIndent()

        val statuses = GhCliClient.parseGraphQlResponse(repos("shelf"), body)

        assertEquals(1, statuses.size)
        val s = statuses.single()
        assertNull(s.error)
        assertEquals(CiStatus.SUCCESS, s.ci)
        assertEquals("CI", s.ciDetail)
        assertEquals(3, s.openPrs)
        assertEquals("abcdef1", s.lastCommit?.sha)
        assertEquals("do the thing", s.lastCommit?.message)
        assertEquals("alkom", s.lastCommit?.author)
    }

    @Test
    fun `a failing check suite outranks a passing one on the same commit`() {
        val body = """
            {"data":{"r0":{
                "pullRequests":{"totalCount":0},
                "defaultBranchRef":{"target":{
                    "oid":"abc","messageHeadline":"m","committedDate":"d","author":{"name":"a"},
                    "checkSuites":{"nodes":[
                        {"status":"COMPLETED","conclusion":"SUCCESS","workflowRun":{"workflow":{"name":"lint"}}},
                        {"status":"COMPLETED","conclusion":"FAILURE","workflowRun":{"workflow":{"name":"tests"}}}
                    ]}
                }}
            }}}
        """.trimIndent()

        val s = GhCliClient.parseGraphQlResponse(repos("shelf"), body).single()

        assertEquals(CiStatus.FAILURE, s.ci)
        assertEquals("tests", s.ciDetail)
    }

    @Test
    fun `an in-progress check suite reports pending even if another already succeeded`() {
        val body = """
            {"data":{"r0":{
                "pullRequests":{"totalCount":0},
                "defaultBranchRef":{"target":{
                    "oid":"abc","messageHeadline":"m","committedDate":"d","author":{"name":"a"},
                    "checkSuites":{"nodes":[
                        {"status":"COMPLETED","conclusion":"SUCCESS","workflowRun":{"workflow":{"name":"lint"}}},
                        {"status":"IN_PROGRESS","conclusion":null,"workflowRun":{"workflow":{"name":"tests"}}}
                    ]}
                }}
            }}}
        """.trimIndent()

        val s = GhCliClient.parseGraphQlResponse(repos("shelf"), body).single()

        assertEquals(CiStatus.PENDING, s.ci)
    }

    @Test
    fun `a phantom check suite with no workflow run does not shadow the real result`() {
        // Observed live against real repos: GitHub attaches a placeholder check
        // suite (QUEUED, conclusion null, workflowRun null) alongside the real one.
        val body = """
            {"data":{"r0":{
                "pullRequests":{"totalCount":0},
                "defaultBranchRef":{"target":{
                    "oid":"abc","messageHeadline":"m","committedDate":"d","author":{"name":"a"},
                    "checkSuites":{"nodes":[
                        {"status":"QUEUED","conclusion":null,"workflowRun":null},
                        {"status":"COMPLETED","conclusion":"SUCCESS","workflowRun":{"workflow":{"name":"CI"}}}
                    ]}
                }}
            }}}
        """.trimIndent()

        val s = GhCliClient.parseGraphQlResponse(repos("shelf"), body).single()

        assertEquals(CiStatus.SUCCESS, s.ci)
        assertEquals("CI", s.ciDetail)
    }

    @Test
    fun `no check suites reports unknown ci with a no-runs detail`() {
        val body = """
            {"data":{"r0":{
                "pullRequests":{"totalCount":0},
                "defaultBranchRef":{"target":{
                    "oid":"abc","messageHeadline":"m","committedDate":"d","author":{"name":"a"},
                    "checkSuites":{"nodes":[]}
                }}
            }}}
        """.trimIndent()

        val s = GhCliClient.parseGraphQlResponse(repos("shelf"), body).single()

        assertEquals(CiStatus.UNKNOWN, s.ci)
        assertEquals("no runs", s.ciDetail)
    }

    @Test
    fun `an empty repo with no default-branch commit still reports open pr count`() {
        val body = """{"data":{"r0":{"pullRequests":{"totalCount":1},"defaultBranchRef":null}}}"""

        val s = GhCliClient.parseGraphQlResponse(repos("shelf"), body).single()

        assertNull(s.error)
        assertNull(s.lastCommit)
        assertEquals("no commits", s.ciDetail)
        assertEquals(1, s.openPrs)
    }

    @Test
    fun `a repo missing from data with a matching graphql error degrades only that repo`() {
        val body = """
            {"data":{"r0":{
                "pullRequests":{"totalCount":0},
                "defaultBranchRef":{"target":{
                    "oid":"abc","messageHeadline":"m","committedDate":"d","author":{"name":"a"},
                    "checkSuites":{"nodes":[]}
                }}
            },"r1":null},
            "errors":[{"type":"NOT_FOUND","path":["r1"],"message":"Could not resolve to a Repository."}]}
        """.trimIndent()

        val statuses = GhCliClient.parseGraphQlResponse(repos("shelf", "ghost"), body)

        assertEquals(2, statuses.size)
        assertNull(statuses[0].error)
        assertEquals("repository not found or inaccessible", statuses[1].error)
    }

    @Test
    fun `a real non-Actions check suite is not dropped like a phantom one`() {
        // workflowRun is null-by-schema-design for check suites created by
        // non-Actions integrations (third-party CI via the Checks API) — only the
        // exact QUEUED+null-conclusion+null-workflowRun phantom shape is dropped.
        val body = """
            {"data":{"r0":{
                "pullRequests":{"totalCount":0},
                "defaultBranchRef":{"target":{
                    "oid":"abc","messageHeadline":"m","committedDate":"d","author":{"name":"a"},
                    "checkSuites":{"nodes":[
                        {"status":"COMPLETED","conclusion":"FAILURE","workflowRun":null}
                    ]}
                }}
            }}}
        """.trimIndent()

        val s = GhCliClient.parseGraphQlResponse(repos("shelf"), body).single()

        assertEquals(CiStatus.FAILURE, s.ci)
    }

    @Test
    fun `a whole-batch graphql error without a path surfaces its message instead of a generic one`() {
        val body = """{"data":{"r0":null},"errors":[{"message":"API rate limit exceeded"}]}"""

        val s = GhCliClient.parseGraphQlResponse(repos("shelf"), body).single()

        assertEquals("graphql error: API rate limit exceeded", s.error)
    }

    @Test
    fun `buildQuery escapes quotes, backslashes and newlines in owner and name`() {
        val query = GhCliClient.buildQuery(listOf(RepoConfig(name = "weird\"na\\me\nend", owner = "own\"er")))

        // A syntactically valid GraphQL string literal never contains a raw,
        // unescaped newline or unescaped quote inside its opening/closing quotes.
        assertTrue(query.contains("\\n"))
        assertTrue(query.contains("\\\""))
        assertTrue(query.contains("\\\\"))
    }
}
