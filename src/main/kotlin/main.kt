package com.example

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.ktor.server.http.content.*
import kotlin.time.Duration.Companion.seconds

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        module()
    }.start(wait = true)
}

fun Application.module() {

    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
    }

    val clients = mutableListOf<WebSocketSession>()

    routing {

        staticResources("/", "")

        webSocket("/ws") {

            clients.add(this)

            println("接続人数: ${clients.size}")

            send("チャット接続成功!")

            try {

                for (frame in incoming) {

                    if (frame is Frame.Text) {

                        val text = frame.readText()

                        println("受信: $text")

                        clients.forEach { client ->
                            client.send("誰か: $text")
                        }
                    }
                }

            } finally {

                clients.remove(this)

                println("切断")
            }
        }
    }
}