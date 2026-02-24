package net.planerist.ktsrpc.protocol.subscriptions

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import net.planerist.ktsrpc.protocol.common.ExecutionResult
import net.planerist.ktsrpc.protocol.subscriptions.messages.SubscriptionServerOperationMessage
import net.planerist.ktsrpc.protocol.subscriptions.messages.SubscriptionServerOperationMessage.ServerMessages.*
import kotlin.coroutines.CoroutineContext

@ExperimentalCoroutinesApi
class Subscription<TContext>(
    parentContext: CoroutineContext,
    private val jsonSerializer: Json,
    private val id: String,
    private val subscriptionHandler: RpcSubscriptionHandler<TContext>,
) : CoroutineScope by CoroutineScope(parentContext + Job(parentContext[Job]) + CoroutineName("ws-subscription-$id")) {

    companion object {
        private val logger = KotlinLogging.logger {}
    }

    suspend fun handleSubscription(
        request: JsonObject,
        context: TContext
    ): Flow<SubscriptionServerOperationMessage> {
        return try {
            withContext(coroutineContext) {
                val subFlow = subscriptionHandler.executeSubscription(request, context)

                channelFlow {
                    val channel = this@channelFlow.channel

                    subFlow
                        .map {
                            if (it.error != null) {
                                SubscriptionServerOperationMessage(
                                    type = MSG_ERROR.type,
                                    id = id,
                                    payload = it.toJon(jsonSerializer)
                                )
                            } else {
                                SubscriptionServerOperationMessage(
                                    type = MSG_DATA.type,
                                    id = id,
                                    payload = it.toJon(jsonSerializer)
                                )
                            }
                        }
                        .onEach { channel.send(it) }
                        .onCompletion {
                            if (this@Subscription.isActive) {
                                channel.send(
                                    SubscriptionServerOperationMessage(
                                        type = MSG_COMPLETE.type,
                                        id = id
                                    )
                                )
                            }
                            channel.close()
                        }
                        .launchIn(this@Subscription)

                    awaitClose()
                }
            }
        } catch (exception: Exception) {
            if(exception !is CancellationException) {
                logger.error(exception) { "Error running rpc subscription" }
            }
            // Do not terminate the session, just stop the operation messages

            flowOf(
                SubscriptionServerOperationMessage(
                    type = MSG_CONNECTION_ERROR.type,
                    id = id,
                    payload =
                        ExecutionResult(
                            error = java.lang.Exception("Error running rpc subscription")
                        )
                            .toJon(jsonSerializer)
                )
            )
        }
    }

    fun stop(): Flow<SubscriptionServerOperationMessage> {
        this.cancel()
        return flowOf(SubscriptionServerOperationMessage(type = MSG_COMPLETE.type, id = id))
    }
}
