package net.planerist.ktsrpc.example

import kotlinx.serialization.Serializable
import net.planerist.ktsrpc.protocol.Rpc

/**
 * Simple greeting response returned by the greeter service.
 */
@Serializable
data class Greeting(
    val message: String,
    val timestamp: String
)

/**
 * Example RPC service demonstrating a simple query (request → response).
 *
 * Usage:
 *   val greeting = rpc.greet("World")   // → Greeting("Hello, World!", "2024-01-01T00:00:00Z")
 */
interface GreeterServiceRpc : Rpc {
    /**
     * Returns a personalized greeting.
     * Demonstrates: simple RPC call with request parameter and response DTO.
     */
    suspend fun greet(name: String): Greeting

    /**
     * Returns a greeting for the authenticated user.
     * Demonstrates: using @RpcContext to access session data.
     */
    suspend fun greetMe(@RpcContext context: RpcContextData): Greeting
}
