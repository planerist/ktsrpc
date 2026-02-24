package net.planerist.ktsrpc.example.server

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json

/**
 * Example Ktor application demonstrating Yaranga RPC framework.
 *
 * Exposes:
 *   POST /rpc      — RPC calls (queries & mutations)
 *   WS   /rpc/ws   — RPC subscriptions (real-time streams)
 *   POST /auth/login — JWT token endpoint (demo)
 */
fun main() {
    embeddedServer(Netty, port = 8080) {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        install(ContentNegotiation) {
            json(json)
        }

        configureAuth()
        configureRpc(json)
    }.start(wait = true)
}
