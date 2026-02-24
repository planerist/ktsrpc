package net.planerist.ktsrpc.example.server

import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.planerist.ktsrpc.example.RpcContext
import net.planerist.ktsrpc.example.RpcContextData
import net.planerist.ktsrpc.example.GreeterServiceRpc
import net.planerist.ktsrpc.example.TodoServiceRpc
import net.planerist.ktsrpc.example.ChatServiceRpc
import net.planerist.ktsrpc.protocol.common.ExecutionJsonRequest
import net.planerist.ktsrpc.protocol.common.RpcCallHandler
import net.planerist.ktsrpc.protocol.subscriptions.RpcSubscriptionHandler
import net.planerist.ktsrpc.protocol.subscriptions.WebSocketHandler
import java.util.zip.Deflater
import kotlin.time.Duration.Companion.seconds

/**
 * Wires up the RPC framework with Ktor.
 *
 * This is the key integration point — copy and adapt this pattern for your own project.
 */
fun Application.configureRpc(json: Json) {
    install(WebSockets) {
        contentConverter = KotlinxWebsocketSerializationConverter(Json)
        timeout = 30.seconds

        extensions {
            install(WebSocketDeflateExtension) {
                compressionLevel = Deflater.DEFAULT_COMPRESSION
                compressIfBiggerThan(bytes = 2 * 1024)
            }
        }
    }

    // Create service implementations
    val greeterService = GreeterService()
    val todoService = TodoService()
    val chatService = ChatService()

    // Register all RPC service implementations
    val rpcCallHandler = RpcCallHandler<RpcContextData>(
        contextAnnotation = RpcContext::class,
        jsonSerializer = json,
        rpcImpls = mapOf(
            GreeterServiceRpc::class to greeterService,
            TodoServiceRpc::class to todoService,
            ChatServiceRpc::class to chatService,
        )
    )

    val rpcSubscriptionHandler = RpcSubscriptionHandler<RpcContextData>(
        rpcCallHandler = rpcCallHandler
    )

    val webSocketHandler = WebSocketHandler<RpcContextData>(
        jsonSerializer = json,
        rpcSubscriptionHandler = rpcSubscriptionHandler,
        contextBuilder = { session ->
            // Extract userId from JWT token in WebSocket query parameters
            // In production, you might extract from the upgrade request headers
            val token = session.call.request.queryParameters["token"]
            val userId = token?.let {
                try {
                    JwtConfig.verifier.verify(it).getClaim("userId").asLong()
                } catch (_: Exception) { null }
            }
            RpcContextData(userId = userId)
        }
    )

    routing {
        // HTTP RPC endpoint (queries & mutations)
        authenticate("auth-jwt", optional = true) {
            post("/rpc") {
                val body = call.receive<JsonObject>()
                val operationName = (body["operationName"] as? JsonPrimitive)?.content
                    ?: throw IllegalArgumentException("operationName is required")

                val query = body["query"] as? JsonObject
                    ?: throw IllegalArgumentException("query object is required")

                // Extract userId from JWT principal (null if unauthenticated)
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()

                val request = ExecutionJsonRequest(
                    methodName = operationName,
                    query = query,
                    context = RpcContextData(userId = userId)
                )

                val result = rpcCallHandler.makeCall(request)
                val responseJson = result.toJon(json)
                call.respond(responseJson)
            }
        }

        // WebSocket RPC endpoint (subscriptions)
        webSocket("/rpc/ws") {
            webSocketHandler.handle(this)
        }
    }
}
