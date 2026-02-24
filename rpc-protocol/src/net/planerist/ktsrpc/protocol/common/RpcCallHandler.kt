package net.planerist.ktsrpc.protocol.common

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.plugins.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.callSuspendBy
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.findAnnotations

class RpcCallHandler<TContext>(
    val contextAnnotation: KClass<out Annotation>?,
    val jsonSerializer: Json,
    rpcImpls: Map<KClass<*>, Any>,
) {
    companion object {
        val logger = KotlinLogging.logger {}
    }

    private data class FunctionWithInstance(
        val func: KFunction<*>,
        val instance: Any,
        val contextParamIndex: Int?,
    )

    private val methods: Map<String, FunctionWithInstance> = rpcImpls.entries
        .flatMap { (interfaceKClass, rpc) ->
            val interfaceFunctions = interfaceKClass.declaredFunctions.associateBy { it.name }
            rpc::class.declaredFunctions.map { implFunc ->
                val contextIndex = if (contextAnnotation != null) {
                    val interfaceFunc = interfaceFunctions[implFunc.name]
                    implFunc.parameters
                        .firstOrNull { param ->
                            param.kind != KParameter.Kind.INSTANCE && (
                                param.findAnnotations(contextAnnotation).isNotEmpty() ||
                                    interfaceFunc?.parameters
                                        ?.getOrNull(param.index)
                                        ?.findAnnotations(contextAnnotation)
                                        ?.isNotEmpty() == true
                            )
                        }
                        ?.index
                } else {
                    null
                }
                implFunc.name to FunctionWithInstance(implFunc, rpc, contextIndex)
            }
        }
        .toMap()

    fun hasMethod(methodName: String?) = methods.containsKey(methodName)

    private fun prepareRequest(request: ExecutionJsonRequest<TContext>): PreparedExecutionRequest {
        val method =
            methods[request.methodName]
                ?: throw NotFoundException("Method ${request.methodName} is not found")

        val arguments =
            method.func.parameters.associateWith {
                if (it.kind == KParameter.Kind.INSTANCE) {
                    method.instance
                } else if (it.index == method.contextParamIndex) {
                    request.context
                } else {
                    val paramValue = request.query[it.name]
                    if (paramValue == null && !it.isOptional && !it.type.isMarkedNullable) {
                        throw Exception(
                            "RPC call '${request.methodName}' missed parameter '${it.name}'"
                        )
                    }

                    val deserializedParamValue =
                        if (paramValue == null) {
                            null
                        } else {
                            val paramValueSerializer = jsonSerializer.serializersModule.serializer(it.type)
                            jsonSerializer.decodeFromJsonElement(paramValueSerializer, paramValue)
                        }

                    deserializedParamValue
                }
            }

        return PreparedExecutionRequest(method.func, arguments)
    }

    suspend fun makeCall(jsonRequest: ExecutionJsonRequest<TContext>): ExecutionResult {
        return try {
            val request = prepareRequest(jsonRequest)
            // TODO: use special coroutine context???
            val retValue = request.method.callSuspendBy(request.arguments)
            ExecutionResult(ReturnDataWithType(retValue, request.method.returnType), null)
        } catch (ex: Throwable) {
            val exception = if (ex is InvocationTargetException) ex.targetException else ex

            return if (exception is RpcError) {
                logger.warn(exception) { "Error executing RPC method, methodName=${jsonRequest.methodName}, query=${jsonRequest.query}" }
                ExecutionResult(null, exception)
            } else if (exception is UnauthorizedException) {
                ExecutionResult(null, Exception("Unauthorized"))
            } else if (exception is CancellationException) {
                ExecutionResult(null, Exception("Cancelled"))
            } else {
                logger.error(exception) { "Error executing RPC method, methodName=${jsonRequest.methodName}, query=${jsonRequest.query}" }
                ExecutionResult(null, Exception("Internal server error"))
            }
        }
    }

    fun getFlowReturnType(methodName: String): KType {
        return methods[methodName]!!.func.returnType.arguments[0].type!!
    }
}
