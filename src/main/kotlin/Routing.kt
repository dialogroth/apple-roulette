package com.example

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.resources.*
import io.ktor.server.resources.*
import io.ktor.server.resources.Resources
import kotlinx.serialization.Serializable
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import java.time.Duration
import io.ktor.server.http.content.*
import io.ktor.server.sse.*
import io.ktor.sse.*

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText("Hello, World!")
        }
        get<Articles> { article ->
            // Get all articles ...
            call.respond("List of articles sorted starting from ${article.sort}")
        }
        webSocket("/ws") { // websocketSession
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    outgoing.send(Frame.Text("YOU SAID: $text"))
                    if (text.equals("bye", ignoreCase = true)) {
                        close(CloseReason(CloseReason.Codes.NORMAL, "Client said BYE"))
                    }
                }
            }
        }
        staticResources("/static", "static")
        get("/json/kotlinx-serialization") {
            call.respond(mapOf("hello" to "world"))
        }
        sse("/hello") {
            send(ServerSentEvent("world"))
        }
    }
}