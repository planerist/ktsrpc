package net.planerist.ktsrpc.protocol.common

import kotlinx.serialization.json.JsonObject

class ExecutionJsonRequest<TContext>(val methodName: String, val query: JsonObject, val context: TContext)

