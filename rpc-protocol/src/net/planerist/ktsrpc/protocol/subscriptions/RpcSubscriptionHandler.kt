package net.planerist.ktsrpc.protocol.subscriptions

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.planerist.ktsrpc.protocol.common.ExecutionJsonRequest
import net.planerist.ktsrpc.protocol.common.ExecutionResult
import net.planerist.ktsrpc.protocol.common.ReturnDataWithType
import net.planerist.ktsrpc.protocol.common.RpcCallHandler

/** Default implementation of GraphQL subscription handler. */
open class RpcSubscriptionHandler<TContext>(
    private val rpcCallHandler: RpcCallHandler<TContext>,
) {
    companion object {
        val logger = KotlinLogging.logger {}
    }

    suspend fun executeSubscription(request: JsonObject, context: TContext): Flow<ExecutionResult> {
        val methodName = (request["operationName"] as JsonPrimitive).content
        val query = request["query"] as JsonObject // TODO assert not null?
        val executionResult = rpcCallHandler.makeCall(ExecutionJsonRequest(methodName, query, context))
        val returnType = rpcCallHandler.getFlowReturnType(methodName)

        return if (executionResult.error != null) {
            flowOf(executionResult)
        } else {
            (executionResult.data!!.value as Flow<*>)
                .map { result -> ExecutionResult(data = ReturnDataWithType(result, returnType)) }
                .catch { throwable ->
                    logger.error(throwable) { "subscription executionResult" }
                    emit(ExecutionResult(error = throwable))
                }
        }
    }
}
