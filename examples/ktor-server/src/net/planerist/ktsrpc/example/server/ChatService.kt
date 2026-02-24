package net.planerist.ktsrpc.example.server

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onStart
import net.planerist.ktsrpc.example.ChatEvent
import net.planerist.ktsrpc.example.ChatServiceRpc
import net.planerist.ktsrpc.example.RpcContext
import net.planerist.ktsrpc.example.RpcContextData
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Chat service demonstrating sealed class polymorphism over WebSocket subscriptions.
 *
 * Uses MutableSharedFlow so multiple subscribers receive the same events.
 */
class ChatService : ChatServiceRpc {

    // room → SharedFlow of events
    private val rooms = ConcurrentHashMap<String, MutableSharedFlow<ChatEvent>>()

    private fun roomFlow(room: String): MutableSharedFlow<ChatEvent> =
        rooms.getOrPut(room) { MutableSharedFlow(replay = 10) }

    override suspend fun sendMessage(@RpcContext context: RpcContextData, room: String, text: String) {
        val userId = context.userId?.toString() ?: "anonymous"
        roomFlow(room).emit(
            ChatEvent.ChatMessage(
                userId = userId,
                text = text,
                timestamp = Instant.now().toString()
            )
        )
    }

    override suspend fun subscribeChatEvents(@RpcContext context: RpcContextData, room: String): Flow<ChatEvent> {
        val userId = context.userId?.toString() ?: "anonymous"
        val flow = roomFlow(room)

        return flow.onStart {
            flow.emit(ChatEvent.UserJoined(userId = userId, name = "User $userId"))
        }
    }
}
