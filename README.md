# kts-rpc

[![JitPack](https://jitpack.io/v/planerist/ktsrpc.svg)](https://jitpack.io/#planerist/ktsrpc)
[![Build](https://jitpack.io/v/planerist/ktsrpc/month.svg)](https://jitpack.io/#planerist/ktsrpc)

**Kotlin + TypeScript RPC** — A code-first RPC framework that generates type-safe TypeScript clients from Kotlin interfaces.

Define your API as Kotlin interfaces → run the generator → get a fully typed TypeScript client with support for queries, mutations, and real-time subscriptions over WebSockets.

## Features

- **Code-first** — Define RPC interfaces in Kotlin, generate TypeScript automatically
- **Type-safe** — Full type safety on both ends (Kotlin ↔ TypeScript)
- **HTTP + WebSocket** — Queries/mutations over HTTP POST, subscriptions over WebSocket
- **Sealed classes → Discriminated unions** — Kotlin sealed hierarchies become TypeScript unions with type guards
- **Subscriptions** — Kotlin `Flow` → TypeScript `AsyncIterable` with automatic WebSocket management
- **Auth-agnostic** — Generic `@RpcContext` annotation lets you inject any request context (JWT, sessions, etc.)

## Architecture

```
┌─────────────────────────────────────────────────────┐
│  Your Project                                       │
│                                                     │
│  ┌─────────────┐    ┌──────────┐    ┌────────────┐  │
│  │ rpc-protocol│◄───│ your     │───►│ rpc-gen    │  │
│  │ (framework) │    │ schema   │    │ (codegen)  │  │
│  └──────┬──────┘    └──────────┘    └─────┬──────┘  │
│         │                                 │         │
│         ▼                                 ▼         │
│  ┌─────────────┐                  ┌──────────────┐  │
│  │ Ktor Server │                  │ rpc.ts       │  │
│  │ POST /rpc   │◄────────────────►│ (generated)  │  │
│  │ WS /rpc/ws  │    HTTP / WS     │              │  │
│  └─────────────┘                  └──────────────┘  │
│                                   + client-runtime  │
└─────────────────────────────────────────────────────┘
```

## Project Structure

```
kts-rpc/
├── rpc-protocol/       # Core: Ktor HTTP & WebSocket RPC handlers
├── rpc-gen/            # Core: TypeScript code generator
├── client-runtime/     # Core: TypeScript client (runtime.ts, rpc-proxy.ts, websocket-client.ts)
├── examples/
│   ├── schema/         # Example: Kotlin RPC interfaces & DTOs
│   ├── ktor-server/    # Example: Ktor backend with JWT auth
│   └── frontend/       # Example: Vite + TypeScript frontend
└── doc/                # Documentation
```

## Quick Start

### 1. Add Dependencies

**Using JitPack** (recommended):

```kotlin
// settings.gradle.kts
repositories {
    maven("https://jitpack.io")
}

// your-schema/build.gradle.kts
dependencies {
    implementation("com.github.planerist.ktsrpc:rpc-protocol:v0.1.3")
    implementation("com.github.planerist.ktsrpc:rpc-gen:v0.1.3")
}
```

**Or copy modules** — clone this repo and copy `rpc-protocol/` and `rpc-gen/` into your Gradle project.

### 2. Define Your RPC Schema

```kotlin
// your-schema/src/.../MyServiceRpc.kt
@Serializable
data class Greeting(val message: String)

interface GreeterServiceRpc : Rpc {
    suspend fun greet(name: String): Greeting
    suspend fun greetMe(@RpcContext ctx: MyContext): Greeting
}
```

### 3. Generate TypeScript

Add the generator task to your schema module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":rpc-protocol"))
    implementation(project(":rpc-gen"))
}

tasks.register<JavaExec>("generateTsRpc") {
    dependsOn("assemble")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("net.planerist.ktsrpc.gen.RpcExporter")
    args = listOf(
        "--output", "../frontend/src/api/rpc.ts",
        "com.example.GreeterServiceRpc",
        "com.example.TodoServiceRpc",
    )
}
```

Run: `./gradlew your-schema:generateTsRpc`

### 4. Wire Ktor Server

See [`examples/ktor-server/src/.../RpcConfig.kt`](examples/ktor-server/src/net/planerist/ktsrpc/example/server/RpcConfig.kt) for the full example. Key setup:

```kotlin
val rpcCallHandler = RpcCallHandler<MyContext>(
    contextAnnotation = RpcContext::class,
    jsonSerializer = json,
    rpcImpls = mapOf(GreeterServiceRpc::class to GreeterService())
)

routing {
    post("/rpc") { /* handle HTTP RPC */ }
    webSocket("/rpc/ws") { webSocketHandler.handle(this) }
}
```

### 5. Frontend

Copy the 3 files from `client-runtime/` into your frontend project, then:

```typescript
import { WebSocketsRpcProxy } from "./api/rpc-proxy";
import { Rpc } from "./api/rpc"; // generated

const rpc = new Rpc(new WebSocketsRpcProxy("/rpc", "/rpc/ws"));
const greeting = await rpc.greet("World");

// Subscriptions
for await (const todos of rpc.subscribeTodos()) {
    renderTodos(todos);
}
```

## Running the Example

```bash
# Backend
./gradlew examples:ktor-server:run

# Frontend (separate terminal)
cd examples/frontend
npm install
npm run dev
```

Open http://localhost:5173 — login, try the greeter, add todos with live updates, join the chat room.

## Supported Types

| Kotlin | TypeScript |
|---|---|
| `String`, `Int`, `Long`, `Double`, `Boolean` | `string`, `number`, `number`, `number`, `boolean` |
| `List<T>`, `Set<T>` | `readonly T[]` |
| `Map<K, V>` | `Record<K, V>` |
| `T?` (nullable) | `T \| null` |
| `@Serializable data class` | `interface` |
| `@Serializable enum class` | `type` (string union) |
| `sealed interface/class` | Discriminated union + type guards |
| `Flow<T>` (return only) | `AsyncIterable<T>` |

See [RELEASING.md](RELEASING.md) for how to publish a new version.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
