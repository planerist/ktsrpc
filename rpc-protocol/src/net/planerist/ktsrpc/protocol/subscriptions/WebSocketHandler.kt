package net.planerist.ktsrpc.protocol.subscriptions

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.serialization.*
import io.ktor.server.websocket.*
import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.core.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import net.planerist.ktsrpc.protocol.common.UnauthorizedException
import net.planerist.ktsrpc.protocol.subscriptions.messages.SubscriptionClientOperationMessage
import java.lang.reflect.Type
import java.nio.charset.Charset
import kotlin.time.Duration

class WebSocketHandler<TContext>(
    private val jsonSerializer: Json,
    private val rpcSubscriptionHandler: RpcSubscriptionHandler<TContext>,
    private val contextBuilder: (webSocketSession: DefaultWebSocketServerSession) -> TContext,
    private val keepAliveInterval: Duration? = null
) {
    companion object {
        val logger = KotlinLogging.logger {}
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    suspend fun handle(webSocketSession: DefaultWebSocketServerSession) {
        val converter =
            webSocketSession.converter
                ?: throw WebsocketConverterNotFoundException("No converter was found for websocket")

        val subscriptionHandler =
            SubscriptionProtocolHandler(
                webSocketSession,
                jsonSerializer,
                rpcSubscriptionHandler,
                contextBuilder,
                keepAliveInterval?.inWholeMilliseconds
            )

        try {
            for (frame in webSocketSession.incoming) {
                webSocketSession.ensureActive()

                when (frame) {
                    is Frame.Text -> {
                        val message =
                            readDeserialized<SubscriptionClientOperationMessage>(
                                converter,
                                webSocketSession.call.request.headers.suitableCharset(),
                                frame
                            )

                        val flow = subscriptionHandler.handle(message)
                        flow
                            .onEach { webSocketSession.sendSerialized(it) }
                            .catch { e ->
                                if (e !is CancellationException && e !is UnauthorizedException) {
                                    logger.error(e) { "Error processing websocket message" }
                                }

                                webSocketSession.cancel()
                            }
                            .launchIn(webSocketSession) // trigger collection of the flow
                    }

                    else -> {}
                }
            }
        } finally {
            webSocketSession.cancel()
        }
    }

    private suspend inline fun <reified T> readDeserialized(
        converter: WebsocketContentConverter,
        charset: Charset,
        frame: Frame.Text
    ): T {
        if (!converter.isApplicable(frame)) {
            throw WebsocketDeserializeException(
                "Converter doesn't support frame type ${frame.frameType.name}",
                frame = frame
            )
        }

        val result =
            deserialize(converter, charset = charset, typeInfo = T::class.java, content = frame)

        if (result is T) return result

        throw WebsocketDeserializeException(
            "Can't deserialize value : expected value of type ${T::class.simpleName}," +
                    " got ${result::class.simpleName}",
            frame = frame
        )
    }

    private suspend fun deserialize(
        converter: WebsocketContentConverter,
        charset: Charset,
        typeInfo: Type,
        content: Frame
    ): Any {
        if (!converter.isApplicable(content)) {
            throw WebsocketConverterNotFoundException("Unsupported frame ${content.frameType.name}")
        }
        try {
            return withContext(Dispatchers.IO) {
                val data = charset.newDecoder().decode(buildPacket { writeFully(content.readBytes()) })
                jsonSerializer.decodeFromString(jsonSerializer.serializersModule.serializer(typeInfo), data)
            }
        } catch (deserializeFailure: Exception) {
            logger.info(deserializeFailure) { "Illegal json parameter found" }
            throw deserializeFailure
        }
    }
}
