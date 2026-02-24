package net.planerist.ktsrpc.protocol.subscriptions.messages

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import net.planerist.ktsrpc.protocol.subscriptions.messages.SubscriptionServerOperationMessage.ServerMessages.MSG_COMPLETE
import net.planerist.ktsrpc.protocol.subscriptions.messages.SubscriptionServerOperationMessage.ServerMessages.MSG_CONNECTION_ERROR

/**
 * The `graphql-ws` protocol from Apollo Client has some special text messages to signal events. Along with the HTTP
 * WebSocket event handling we need to have some extra logic
 *
 * https://github.com/apollographql/subscriptions-transport-ws/blob/master/PROTOCOL.md
 */
@Serializable
data class SubscriptionClientOperationMessage(
    val type: String,
    val id: String? = null,
    val payload: JsonObject? = null
) {
    enum class ClientMessages(val type: String) {
        MSG_CONNECTION_INIT("connection_init"),
        MSG_SUBSCRIBE("subscribe"),
        MSG_STOP("complete"),
        MSG_PING("ping"),
        MSG_CONNECTION_TERMINATE("connection_terminate")
    }
}

val terminalMessages = setOf(MSG_CONNECTION_ERROR.type, MSG_COMPLETE.type)
