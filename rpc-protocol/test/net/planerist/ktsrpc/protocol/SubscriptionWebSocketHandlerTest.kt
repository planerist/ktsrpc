package net.planerist.ktsrpc.protocol

import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.*
import io.ktor.server.testing.*
import io.ktor.server.websocket.*
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.planerist.ktsrpc.protocol.common.RpcCallHandler
import net.planerist.ktsrpc.protocol.subscriptions.RpcSubscriptionHandler
import net.planerist.ktsrpc.protocol.subscriptions.WebSocketHandler
import net.planerist.ktsrpc.protocol.subscriptions.messages.SubscriptionClientOperationMessage
import net.planerist.ktsrpc.protocol.subscriptions.messages.SubscriptionClientOperationMessage.ClientMessages.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SubscriptionWebSocketHandlerTest {
    @Suppress("unused")
    object SimpleQuery {
        fun query(): String = "hello!"
    }

    @Suppress("unused")
    object Subscription1 {
        fun messageAndClose(): Flow<String> {
            return flowOf("message")
        }

        fun echo1(message: String): Flow<String> {
            return flowOf(message)
        }

        fun threeWithDelay1(): Flow<String> {
            return flow {
                emit("msg1.1")
                delay(1000)

                emit("msg1.2")
                delay(1000)

                emit("msg1.3")
            }
        }

        fun threeWithDelay2(): Flow<String> {
            return flow {
                delay(500)
                emit("msg2.1")
                delay(1000)

                emit("msg2.2")
                delay(1000)

                emit("msg2.3")
                delay(1000)
            }
        }

        var check1: Boolean = true

        fun threeWithDelay3(): Flow<String> {
            return flow {
                emit("msg1.1")
                delay(1000)
                emit(
                    "We always emit at least one message after cancel due to flow cancellation nature"
                )

                while (true) {
                    check1 = false
                    emit("Error!")
                    delay(1000)
                }
            }
                .cancellable()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun messageAndClose() = testApplication {
        val client = setupServerAndClient(Subscription1)

        client.webSocket("/graphql") {
            val initMessage = getInitMessage()
            outgoing.send(Frame.Text(initMessage))
            assertEquals("{\"type\":\"connection_ack\"}", readTextFrame(incoming))

            val request = createGraphQLRequest("messageAndClose")
            val messageId = "3"
            val startMessage = getSubscribeMessage(messageId, request)

            outgoing.send(Frame.Text(startMessage))

            assertEquals(
                "{\"type\":\"next\",\"id\":\"3\",\"payload\":{\"data\":\"message\"}}",
                readTextFrame(incoming)
            )
            assertEquals("{\"type\":\"complete\",\"id\":\"3\"}", readTextFrame(incoming))

            delay(1000)
            assertTrue(incoming.isEmpty)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun initTest() = testApplication {
        val client = setupServerAndClient(Subscription1)

        client.webSocket("/graphql") {
            val initMessage = getInitMessage()

            outgoing.send(Frame.Text(initMessage))

            assertEquals("{\"type\":\"connection_ack\"}", readTextFrame(incoming))

            delay(1000)
            assertTrue(incoming.isEmpty)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun disconnectTest() = testApplication {
        val client = setupServerAndClient(Subscription1)

        client.webSocket("/graphql") {
            val initMessage = getInitMessage()
            outgoing.send(Frame.Text(initMessage))
            assertEquals("{\"type\":\"connection_ack\"}", readTextFrame(incoming))

            val disconnectMessage = getDisconnectMessage()
            outgoing.send(Frame.Text(disconnectMessage))
            assertTrue(receiveClose(incoming))

            delay(1000)
            assertTrue(incoming.isClosedForReceive)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun keepAliveTest() = testApplication {
        val client = setupServerAndClient(Subscription1, 500.milliseconds)

        client.webSocket("/graphql") {
            val initMessage = getInitMessage()

            outgoing.send(Frame.Text(initMessage))
            assertEquals("{\"type\":\"connection_ack\"}", readTextFrame(incoming))

            assertEquals("{\"type\":\"pong\"}", readTextFrame(incoming))
            assertEquals("{\"type\":\"pong\"}", readTextFrame(incoming))

            val disconnectMessage = getDisconnectMessage()
            outgoing.send(Frame.Text(disconnectMessage))
            assertTrue(receiveClose(incoming))

            delay(1000)
            assertTrue(incoming.isClosedForReceive)
        }
    }

    @Test
    fun multipleSubscriptions() = testApplication {
        val client = setupServerAndClient(Subscription1)

        client.webSocket("/graphql") {
            val initMessage = getInitMessage()
            outgoing.send(Frame.Text(initMessage))
            assertEquals("{\"type\":\"connection_ack\"}", readTextFrame(incoming))

            val request = createGraphQLRequest("threeWithDelay1")
            val messageId = "1"
            val startMessage = getSubscribeMessage(messageId, request)
            outgoing.send(Frame.Text(startMessage))

            val request2 = createGraphQLRequest("threeWithDelay2")
            val messageId2 = "2"
            val startMessage2 = getSubscribeMessage(messageId2, request2)
            outgoing.send(Frame.Text(startMessage2))

            assertEquals(
                "{\"type\":\"next\",\"id\":\"1\",\"payload\":{\"data\":\"msg1.1\"}}",
                readTextFrame(incoming)
            )
            assertEquals(
                "{\"type\":\"next\",\"id\":\"2\",\"payload\":{\"data\":\"msg2.1\"}}",
                readTextFrame(incoming)
            )

            assertEquals(
                "{\"type\":\"next\",\"id\":\"1\",\"payload\":{\"data\":\"msg1.2\"}}",
                readTextFrame(incoming)
            )
            assertEquals(
                "{\"type\":\"next\",\"id\":\"2\",\"payload\":{\"data\":\"msg2.2\"}}",
                readTextFrame(incoming)
            )

            assertEquals(
                "{\"type\":\"next\",\"id\":\"1\",\"payload\":{\"data\":\"msg1.3\"}}",
                readTextFrame(incoming)
            )
            assertEquals("{\"type\":\"complete\",\"id\":\"1\"}", readTextFrame(incoming))
            assertEquals(
                "{\"type\":\"next\",\"id\":\"2\",\"payload\":{\"data\":\"msg2.3\"}}",
                readTextFrame(incoming)
            )

            assertEquals("{\"type\":\"complete\",\"id\":\"2\"}", readTextFrame(incoming))

            val disconnectMessage = getDisconnectMessage()
            outgoing.send(Frame.Text(disconnectMessage))
            assertTrue(receiveClose(incoming))
        }
    }

    @Test
    fun multipleSubscriptionsCancel1() = testApplication {
        val client = setupServerAndClient(Subscription1, null)

        client.webSocket("/graphql") {
            val initMessage = getInitMessage()
            outgoing.send(Frame.Text(initMessage))
            assertEquals("{\"type\":\"connection_ack\"}", readTextFrame(incoming))

            val request = createGraphQLRequest("threeWithDelay1")
            val messageId = "1"
            val startMessage = getSubscribeMessage(messageId, request)
            outgoing.send(Frame.Text(startMessage))

            val request2 = createGraphQLRequest("threeWithDelay2")
            val messageId2 = "2"
            val startMessage2 = getSubscribeMessage(messageId2, request2)
            outgoing.send(Frame.Text(startMessage2))

            assertEquals(
                "{\"type\":\"next\",\"id\":\"1\",\"payload\":{\"data\":\"msg1.1\"}}",
                readTextFrame(incoming)
            )

            assertEquals(
                "{\"type\":\"next\",\"id\":\"2\",\"payload\":{\"data\":\"msg2.1\"}}",
                readTextFrame(incoming)
            )

            val stopMessage = getStopMessage(messageId2)
            outgoing.send(Frame.Text(stopMessage))

            assertEquals("{\"type\":\"complete\",\"id\":\"2\"}", readTextFrame(incoming))

            assertEquals(
                "{\"type\":\"next\",\"id\":\"1\",\"payload\":{\"data\":\"msg1.2\"}}",
                readTextFrame(incoming)
            )

            assertEquals(
                "{\"type\":\"next\",\"id\":\"1\",\"payload\":{\"data\":\"msg1.3\"}}",
                readTextFrame(incoming)
            )

            assertEquals("{\"type\":\"complete\",\"id\":\"1\"}", readTextFrame(incoming))

            val disconnectMessage = getDisconnectMessage()
            outgoing.send(Frame.Text(disconnectMessage))
            assertTrue(receiveClose(incoming))
        }
    }

    @Test
    fun echoSubscription() = testApplication {
        val client = setupServerAndClient(Subscription1, 500.milliseconds)

        client.webSocket("/graphql") {
            val initMessage = getInitMessage()
            outgoing.send(Frame.Text(initMessage))
            assertEquals("{\"type\":\"connection_ack\"}", readTextFrame(incoming))

            val request = createGraphQLRequest("echo1", mapOf("message" to JsonPrimitive("msgXXXYYY")))
            val messageId = "1"
            val startMessage = getSubscribeMessage(messageId, request)
            outgoing.send(Frame.Text(startMessage))

            assertEquals(
                "{\"type\":\"next\",\"id\":\"1\",\"payload\":{\"data\":\"msgXXXYYY\"}}",
                readTextFrame(incoming)
            )

            assertEquals("{\"type\":\"complete\",\"id\":\"1\"}", readTextFrame(incoming))

            val disconnectMessage = getDisconnectMessage()
            outgoing.send(Frame.Text(disconnectMessage))
            assertTrue(receiveClose(incoming))

            assertTrue(Subscription1.check1)
        }
    }


    @Test
    fun stopSubscription() = testApplication {
        val client = setupServerAndClient(Subscription1, 500.milliseconds)

        client.webSocket("/graphql") {
            val initMessage = getInitMessage()
            outgoing.send(Frame.Text(initMessage))
            assertEquals("{\"type\":\"connection_ack\"}", readTextFrame(incoming))

            val request = createGraphQLRequest("threeWithDelay3")
            val messageId = "1"
            val startMessage = getSubscribeMessage(messageId, request)
            outgoing.send(Frame.Text(startMessage))

            assertEquals(
                "{\"type\":\"next\",\"id\":\"1\",\"payload\":{\"data\":\"msg1.1\"}}",
                readTextFrame(incoming)
            )

            val stopMessage = getStopMessage(messageId)
            outgoing.send(Frame.Text(stopMessage))

            assertEquals("{\"type\":\"complete\",\"id\":\"1\"}", readTextFrame(incoming))

            assertEquals("{\"type\":\"pong\"}", readTextFrame(incoming))
            assertEquals("{\"type\":\"pong\"}", readTextFrame(incoming))
            assertEquals("{\"type\":\"pong\"}", readTextFrame(incoming))
            assertEquals("{\"type\":\"pong\"}", readTextFrame(incoming))
            assertEquals("{\"type\":\"pong\"}", readTextFrame(incoming))

            val disconnectMessage = getDisconnectMessage()
            outgoing.send(Frame.Text(disconnectMessage))
            assertTrue(receiveClose(incoming))

            assertTrue(Subscription1.check1)
        }
    }

    private fun createGraphQLRequest(
        method: String,
        query: Map<String, JsonElement> = emptyMap()
    ): JsonObject {
        return JsonObject(
            mapOf("operationName" to JsonPrimitive(method), "query" to JsonObject(query))
        )
    }

    private suspend fun receiveClose(incoming: ReceiveChannel<Frame>) =
        withTimeout(10.seconds) {
            try {
                return@withTimeout incoming.receive() is Frame.Close
            } catch (e: ClosedReceiveChannelException) {
                // channel was closed faster than was read, it's ok!
                return@withTimeout true
            }
        }

    private suspend fun readTextFrame(incoming: ReceiveChannel<Frame>) =
        withTimeout(10.seconds) { (incoming.receive() as Frame.Text).readText() }

    private fun ApplicationTestBuilder.setupServerAndClient(
        subscription: Any,
        keepAliveInterval: kotlin.time.Duration? = null
    ): HttpClient {
        val rpcImpls = mapOf(subscription::class to subscription)

        val subscriptionContextBuilder =
            { _: DefaultWebSocketServerSession -> }

        val rpcCallHandler = RpcCallHandler<Unit>(null, jsonSerializer, rpcImpls)
        val webSocketHandler =
            WebSocketHandler(
                jsonSerializer,
                RpcSubscriptionHandler(rpcCallHandler),
                subscriptionContextBuilder,
                keepAliveInterval
            )

        install(WebSockets) {
            timeout = 30.seconds
            contentConverter = KotlinxWebsocketSerializationConverter(jsonSerializer)
        }

        routing {
            webSocket(
                "/graphql", /*"graphql-ws"*/
            ) { // websocketSession
                webSocketHandler.handle(this)
            }
        }

        return createClient { install(io.ktor.client.plugins.websocket.WebSockets) {} }
    }

    private val jsonSerializer = Json

    private fun getSubscribeMessage(id: String, payload: JsonObject) =
        jsonSerializer.encodeToString(
            SubscriptionClientOperationMessage.serializer(),
            SubscriptionClientOperationMessage(MSG_SUBSCRIBE.type, id, payload)
        )

    private fun getStopMessage(id: String) =
        jsonSerializer.encodeToString(
            SubscriptionClientOperationMessage.serializer(),
            SubscriptionClientOperationMessage(MSG_STOP.type, id)
        )

    private fun getInitMessage() =
        jsonSerializer.encodeToString(
            SubscriptionClientOperationMessage.serializer(),
            SubscriptionClientOperationMessage(MSG_CONNECTION_INIT.type)
        )

    private fun getDisconnectMessage() =
        jsonSerializer.encodeToString(
            SubscriptionClientOperationMessage.serializer(),
            SubscriptionClientOperationMessage(MSG_CONNECTION_TERMINATE.type)
        )
}
