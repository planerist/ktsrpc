package net.planerist.ktsrpc.protocol

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.planerist.ktsrpc.protocol.common.ExecutionJsonRequest
import net.planerist.ktsrpc.protocol.common.RpcCallHandler
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests that [RpcCallHandler] correctly detects @RpcContext annotations
 * on both interface and implementation class method parameters, and
 * caches this information at construction time.
 */
class RpcCallHandlerTest {

    // ── Test-local annotation & types ──────────────────────────────────────

    @Target(AnnotationTarget.VALUE_PARAMETER)
    annotation class TestContext

    data class ContextData(val userId: String)

    // ── Interface (has @TestContext) ────────────────────────────────────────

    @Suppress("unused")
    interface ServiceRpc : Rpc {
        suspend fun greet(@TestContext ctx: ContextData, name: String): String
    }

    // ── Interface WITHOUT @TestContext ──────────────────────────────────────

    @Suppress("unused")
    interface ServiceRpcNoAnnotation : Rpc {
        suspend fun greet(ctx: ContextData, name: String): String
    }

    // ── Implementations ────────────────────────────────────────────────────

    /** Annotation only on the interface, NOT on the impl override. */
    @Suppress("unused")
    class ServiceImplWithoutAnnotation : ServiceRpc {
        override suspend fun greet(ctx: ContextData, name: String): String {
            return "Hello, $name! (user=${ctx.userId})"
        }
    }

    /** Annotation on both the interface AND the impl override. */
    @Suppress("unused")
    class ServiceImplWithAnnotation : ServiceRpc {
        override suspend fun greet(@TestContext ctx: ContextData, name: String): String {
            return "Hello, $name! (user=${ctx.userId})"
        }
    }

    /** Annotation only on the impl, NOT on the interface. */
    @Suppress("unused")
    class ServiceImplOnlyAnnotation : ServiceRpcNoAnnotation {
        override suspend fun greet(@TestContext ctx: ContextData, name: String): String {
            return "Hello, $name! (user=${ctx.userId})"
        }
    }

    /** No annotation anywhere — neither interface nor impl. */
    @Suppress("unused")
    class ServiceNeitherAnnotation : ServiceRpcNoAnnotation {
        override suspend fun greet(ctx: ContextData, name: String): String {
            return "Hello, $name! (user=${ctx.userId})"
        }
    }

    private val json = Json

    private fun makeRequest(context: ContextData = ContextData(userId = "42")) =
        ExecutionJsonRequest(
            methodName = "greet",
            query = JsonObject(mapOf("name" to JsonPrimitive("World"))),
            context = context,
        )

    // ── Tests ──────────────────────────────────────────────────────────────

    @Test
    fun `annotation only on interface should inject context successfully`() = runBlocking {
        val handler = RpcCallHandler<ContextData>(
            contextAnnotation = TestContext::class,
            jsonSerializer = json,
            rpcImpls = mapOf(ServiceRpc::class to ServiceImplWithoutAnnotation()),
        )

        val result = handler.makeCall(makeRequest())

        assertNull(result.error, "Expected no error when @TestContext is on interface")
        assertEquals("Hello, World! (user=42)", result.data!!.value)
    }

    @Test
    fun `annotation on both interface and impl should inject context successfully`() = runBlocking {
        val handler = RpcCallHandler<ContextData>(
            contextAnnotation = TestContext::class,
            jsonSerializer = json,
            rpcImpls = mapOf(ServiceRpc::class to ServiceImplWithAnnotation()),
        )

        val result = handler.makeCall(makeRequest())

        assertNull(result.error, "Expected no error when @TestContext is on both")
        assertEquals("Hello, World! (user=42)", result.data!!.value)
    }

    @Test
    fun `annotation only on impl should inject context successfully`() = runBlocking {
        val handler = RpcCallHandler<ContextData>(
            contextAnnotation = TestContext::class,
            jsonSerializer = json,
            rpcImpls = mapOf(ServiceRpcNoAnnotation::class to ServiceImplOnlyAnnotation()),
        )

        val result = handler.makeCall(makeRequest())

        assertNull(result.error, "Expected no error when @TestContext is only on impl")
        assertEquals("Hello, World! (user=42)", result.data!!.value)
    }

    @Test
    fun `no annotation anywhere should treat context param as regular param and fail`() = runBlocking {
        val handler = RpcCallHandler<ContextData>(
            contextAnnotation = TestContext::class,
            jsonSerializer = json,
            rpcImpls = mapOf(ServiceRpcNoAnnotation::class to ServiceNeitherAnnotation()),
        )

        val result = handler.makeCall(makeRequest())

        assertNotNull(result.error, "Expected error when @TestContext is missing everywhere")
    }
}
