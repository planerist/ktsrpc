package net.planerist.ktsrpc.protocol.subscriptions.messages

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class SubscriptionServerOperationMessage(
    val type: String,
    val id: String? = null,
    val payload: JsonElement? = null
) {
    enum class ServerMessages(val type: String) {
        MSG_CONNECTION_ACK("connection_ack"),
        MSG_CONNECTION_ERROR("error"),
        MSG_DATA("next"),
        MSG_ERROR("error"),
        MSG_COMPLETE("complete"),
        MSG_PONG("pong"),
    }
}
