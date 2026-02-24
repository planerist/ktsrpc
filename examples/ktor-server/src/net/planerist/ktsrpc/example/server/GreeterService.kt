package net.planerist.ktsrpc.example.server

import net.planerist.ktsrpc.example.Greeting
import net.planerist.ktsrpc.example.GreeterServiceRpc
import net.planerist.ktsrpc.example.RpcContext
import net.planerist.ktsrpc.example.RpcContextData
import java.time.Instant

class GreeterService : GreeterServiceRpc {

    override suspend fun greet(name: String): Greeting {
        return Greeting(
            message = "Hello, $name!",
            timestamp = Instant.now().toString()
        )
    }

    override suspend fun greetMe(@RpcContext context: RpcContextData): Greeting {
        val who = context.userId?.let { "User #$it" } ?: "Anonymous"
        return Greeting(
            message = "Hello, $who!",
            timestamp = Instant.now().toString()
        )
    }
}
