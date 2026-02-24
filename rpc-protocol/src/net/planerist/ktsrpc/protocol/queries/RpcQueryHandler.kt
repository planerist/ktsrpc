package net.planerist.ktsrpc.protocol.queries

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import net.planerist.ktsrpc.protocol.common.UnauthorizedException
import net.planerist.ktsrpc.protocol.common.ExecutionJsonRequest
import net.planerist.ktsrpc.protocol.common.RpcCallHandler

class RpcQueryHandler<TContext>(
    private val jsonSerializer: Json,
    private val methodParamName: String,
    private val rpcCallHandler: RpcCallHandler<TContext>,
    private val contextBuilder: (ApplicationCall, String) -> TContext
) {
    companion object {
        val logger = KotlinLogging.logger {}
    }

    suspend fun executeQuery(call: ApplicationCall) {
        val methodName = call.parameters[methodParamName]
        if (methodName == null || !rpcCallHandler.hasMethod(methodName)) {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        try {
            val context = contextBuilder(call, methodName)
            val requestBody = withContext(Dispatchers.IO) { call.receiveText() }
            val requestJson = jsonSerializer.parseToJsonElement(requestBody) as JsonObject // TODO: assert not null ??

            val response = rpcCallHandler
                .makeCall(ExecutionJsonRequest(methodName, requestJson, context))
                .toJon(jsonSerializer)

            withContext(Dispatchers.IO) { call.respond(response) }
        } catch (e: Exception) {
            if (e is UnauthorizedException) {
                call.respond(HttpStatusCode.Unauthorized)
            } else {
                logger.error(e) { "Error execution request" }
                call.respond(HttpStatusCode.InternalServerError)
            }
        }
    }
}
