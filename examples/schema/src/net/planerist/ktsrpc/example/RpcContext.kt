package net.planerist.ktsrpc.example

import kotlinx.serialization.Serializable

/**
 * Annotation to mark a parameter that should be injected with request context (e.g. authenticated user).
 * Parameters annotated with @RpcContext are excluded from TypeScript client generation.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class RpcContext

/**
 * Request-scoped context data available to every RPC handler.
 * Populated from authentication (e.g. JWT claims) before the handler is invoked.
 */
@Serializable
data class RpcContextData(
    val userId: Long?
)
