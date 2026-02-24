package net.planerist.ktsrpc.protocol.common

import kotlin.reflect.KFunction
import kotlin.reflect.KParameter

class PreparedExecutionRequest(val method: KFunction<*>, val arguments: Map<KParameter, Any?>)