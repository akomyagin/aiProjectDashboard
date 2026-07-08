package dev.akomyagin.dashboard.http

import dev.akomyagin.dashboard.DashboardService
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json

/**
 * Configures and returns (without starting) the embedded Ktor server that serves
 * the local web dashboard. Binds to loopback (127.0.0.1) only — this is a
 * personal, on-demand tool, never exposed to a network.
 */
fun dashboardServer(service: DashboardService, port: Int) =
    embeddedServer(CIO, host = "127.0.0.1", port = port) {
        installPlugins()
        routes(service)
    }

fun Application.installPlugins() {
    install(ContentNegotiation) {
        json(Json { prettyPrint = true })
    }
    install(CallLogging)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (cause.message ?: "internal error")))
        }
    }
}

fun Application.routes(service: DashboardService) {
    routing {
        // JSON API consumed by the static single-page UI.
        get("/api/status") { call.respond(service.portfolioStatus()) }
        get("/api/todos") { call.respond(service.todos()) }
        get("/api/health") { call.respond(mapOf("status" to "ok")) }

        // Static SPA (index.html + assets) from resources/static.
        staticResources("/", "static", index = "index.html")
    }
}
