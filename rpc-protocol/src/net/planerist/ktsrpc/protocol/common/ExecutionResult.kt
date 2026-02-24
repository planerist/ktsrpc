package net.planerist.ktsrpc.protocol.common

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import kotlin.reflect.KType

class ReturnDataWithType(val value: Any?, val type: KType)

class ExecutionResult(val data: ReturnDataWithType? = null, val error: Throwable? = null) {
    fun toJon(jsonSerializer: Json): JsonObject =
        if (error != null) {
            JsonObject(mapOf("error" to JsonPrimitive(getMessage(error))))
        } else {
            val jsonExecutionResult =
                if (data == null) {
                    JsonNull
                } else {
                    val returnTypeSerializer = jsonSerializer.serializersModule.serializer(data.type)
                    jsonSerializer.encodeToJsonElement(returnTypeSerializer, data.value)
                }

            JsonObject(mapOf("data" to jsonExecutionResult))
        }

    private fun getMessage(error: Throwable?): String {
        if (error == null) {
            return "<none>"
        }

        return if (error.message == null) {
            getMessage(error.cause)
        } else {
            error.message ?: "<none>"
        }
    }
}
