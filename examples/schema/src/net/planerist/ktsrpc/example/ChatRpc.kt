package net.planerist.ktsrpc.example

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import net.planerist.ktsrpc.protocol.Rpc

/**
 * Polymorphic chat event hierarchy.
 * Demonstrates: sealed class → discriminated union in TypeScript.
 *
 * Generated TS will include a `type` discriminator and type guards:
 *   if (isChatMessage(event)) { event.text }
 */
@Serializable
sealed interface ChatEvent {
    @Serializable
    data class ChatMessage(val userId: String, val text: String, val timestamp: String) : ChatEvent

    @Serializable
    data class UserJoined(val userId: String, val name: String) : ChatEvent

    @Serializable
    data class UserLeft(val userId: String) : ChatEvent
}

/**
 * Example RPC service demonstrating real-time chat with sealed class polymorphism.
 */
interface ChatServiceRpc : Rpc {
    /**
     * Sends a message to a chat room.
     */
    suspend fun sendMessage(@RpcContext context: RpcContextData, room: String, text: String)

    /**
     * Subscribes to all events in a chat room (messages, joins, leaves).
     * Demonstrates: Flow of sealed class → WebSocket stream of discriminated union.
     */
    suspend fun subscribeChatEvents(@RpcContext context: RpcContextData, room: String): Flow<ChatEvent>
}
