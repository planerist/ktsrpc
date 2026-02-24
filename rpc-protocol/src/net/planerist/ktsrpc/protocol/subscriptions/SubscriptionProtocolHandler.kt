package net.planerist.ktsrpc.protocol.subscriptions

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import net.planerist.ktsrpc.protocol.common.ExecutionResult
import net.planerist.ktsrpc.protocol.subscriptions.messages.SubscriptionClientOperationMessage
import net.planerist.ktsrpc.protocol.subscriptions.messages.SubscriptionClientOperationMessage.ClientMessages.*
import net.planerist.ktsrpc.protocol.subscriptions.messages.SubscriptionServerOperationMessage
import net.planerist.ktsrpc.protocol.subscriptions.messages.SubscriptionServerOperationMessage.ServerMessages.*
import net.planerist.ktsrpc.protocol.subscriptions.messages.terminalMessages
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

@ExperimentalCoroutinesApi
@FlowPreview
class SubscriptionProtocolHandler<TContext>(
    private val webSocketSession: DefaultWebSocketServerSession,
    private val jsonSerializer: Json,
    private val subscriptionHandler: RpcSubscriptionHandler<TContext>,
    private val contextBuilder: (webSocketSession: DefaultWebSocketServerSession) -> TContext,
    private val keepAliveInterval: Long?
) {
    companion object {
        private val logger = KotlinLogging.logger {}

        private val keepAliveMessage = SubscriptionServerOperationMessage(type = MSG_PONG.type)

        private val pongMessage = SubscriptionServerOperationMessage(type = MSG_PONG.type)

        private val acknowledgeMessage = SubscriptionServerOperationMessage(MSG_CONNECTION_ACK.type)
    }

    private val subscriptions = ConcurrentHashMap<String, Subscription<TContext>>()
    private var callContext: TContext? = null

    suspend fun handle(
        operationMessage: SubscriptionClientOperationMessage
    ): Flow<SubscriptionServerOperationMessage> {
        logger.debug { "RPC subscription client message, operationMessage=$operationMessage" }

        return try {
            when (operationMessage.type) {
                MSG_CONNECTION_INIT.type -> onInit(operationMessage)
                MSG_SUBSCRIBE.type -> startSubscription(operationMessage)
                MSG_STOP.type -> onStop(operationMessage)
                MSG_CONNECTION_TERMINATE.type -> onDisconnect()
                MSG_PING.type -> onPing(operationMessage)
                else -> onUnknownOperation(operationMessage)
            }
        } catch (exception: Exception) {
            onException(exception)
        }
    }

    private fun onInit(
        operationMessage: SubscriptionClientOperationMessage
    ): Flow<SubscriptionServerOperationMessage> {
        val id = operationMessage.id
        val acknowledgeMessage = flowOf(acknowledgeMessage)
        val keepAliveFlow = getKeepAlive()
        callContext =
            contextBuilder(
//                (operationMessage.payload?.get("x-client-id") as? JsonPrimitive)?.content ?: "",
                webSocketSession
            )

        return flowOf(acknowledgeMessage, keepAliveFlow).flattenConcat().catch {
            logger.error(it) { "Error in RPC subscription init sequence" }
            getConnectionErrorMessage(id, "Error in RPC subscription init sequence")
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onPing(
        operationMessage: SubscriptionClientOperationMessage
    ): Flow<SubscriptionServerOperationMessage> {
        return flowOf(pongMessage)
    }

    /**
     * If the keep alive configuration is set, send a message back to client at every interval until
     * the session is terminated. Otherwise just return empty flux to append to the acknowledge
     * message.
     */
    private fun getKeepAlive(): Flow<SubscriptionServerOperationMessage> {
        val keepAliveInterval: Long? = keepAliveInterval

        if (keepAliveInterval != null) {
            return flow {
                while (currentCoroutineContext().isActive) {
                    delay(keepAliveInterval)
                    emit(keepAliveMessage)
                }
            }
        }

        return emptyFlow()
    }

    private suspend fun startSubscription(
        operationMessage: SubscriptionClientOperationMessage,
    ): Flow<SubscriptionServerOperationMessage> {
        if (operationMessage.id == null) {
            logger.error { "RPC subscription operation id is required" }
            return flowOf(
                SubscriptionServerOperationMessage(
                    type = MSG_CONNECTION_ERROR.type,
                    id = "unknown",
                    payload =
                        ExecutionResult(
                            error = Exception("RPC subscription operation id is required")
                        )
                            .toJon(jsonSerializer)
                )
            )
        }

        if (operationMessage.payload == null) {
            logger.error { "RPC subscription payload was null instead of a JsonObject object" }
            return flowOf(
                SubscriptionServerOperationMessage(
                    type = MSG_CONNECTION_ERROR.type,
                    id = operationMessage.id,
                    payload =
                        ExecutionResult(
                            error =
                                Exception(
                                    "RPC subscription payload was null instead of a JsonObject object"
                                )
                        )
                            .toJon(jsonSerializer)
                )
            )
        }

        if (subscriptions.contains(operationMessage.id)) {
            logger.info { "Already subscribed to operation ${operationMessage.id}" }
            return emptyFlow()
        }

        val subscription =
            Subscription(webSocketSession.coroutineContext, jsonSerializer, operationMessage.id, subscriptionHandler)

        subscriptions[operationMessage.id] = subscription

        return subscription
            .handleSubscription(
                operationMessage.payload,
                callContext!!
            ) // TODO: diagnostic & error?
            .transformWhile {
                emit(it)
                !terminalMessages.contains(it.type)
            }
            .catch { e ->
                if (e !is CancellationException)
                    logger.error(e) { "Error processing handleSubscription result flow" }
                else throw e
            }
            .onCompletion { subscriptions.remove(operationMessage.id) }
    }

    private fun onException(exception: Exception): Flow<SubscriptionServerOperationMessage> {
        logger.error(exception) { "Error parsing the subscription message" }
        return flowOf(
            SubscriptionServerOperationMessage(
                type = MSG_CONNECTION_ERROR.type,
                payload =
                    ExecutionResult(error = Exception("Error parsing the subscription message"))
                        .toJon(jsonSerializer)
            )
        )
    }

    private fun getConnectionErrorMessage(
        id: String?,
        message: String
    ): SubscriptionServerOperationMessage {
        return SubscriptionServerOperationMessage(
            type = MSG_CONNECTION_ERROR.type,
            id = id,
            payload = ExecutionResult(error = Exception(message)).toJon(jsonSerializer)
        )
    }

    private fun onUnknownOperation(
        operationMessage: SubscriptionClientOperationMessage,
    ): Flow<SubscriptionServerOperationMessage> {
        logger.error { "Unknown subscription operation $operationMessage" }
        // TODO: stop??
        return flowOf(
            getConnectionErrorMessage(
                operationMessage.id,
                "Unknown subscription operation '${operationMessage.type}'"
            )
        )
    }

    /**
     * Called with the client has called stop manually, or on error, and we need to cancel the
     * publisher
     */
    private fun onStop(
        operationMessage: SubscriptionClientOperationMessage
    ): Flow<SubscriptionServerOperationMessage> {
        if (operationMessage.id != null) {
            return subscriptions[operationMessage.id]?.stop() ?: emptyFlow()
        }

        return emptyFlow()
    }

    private fun onDisconnect(): Flow<SubscriptionServerOperationMessage> {
        webSocketSession.cancel()
        return emptyFlow()
    }
}
