# kts-rpc

[![JitPack](https://jitpack.io/v/planerist/ktsrpc.svg)](https://jitpack.io/#planerist/ktsrpc)
[![Build](https://jitpack.io/v/planerist/ktsrpc/month.svg)](https://jitpack.io/#planerist/ktsrpc)

**Kotlin → TypeScript & Swift RPC** — a code-first RPC framework that generates type-safe TypeScript and Swift clients from Kotlin interfaces.

Define your API as Kotlin interfaces → run the generator → get a fully typed TypeScript client for the web and a Swift client for iOS and macOS, with queries, mutations and real-time subscriptions over WebSockets.

## Features

- **Code-first** — Define RPC interfaces in Kotlin, generate the clients automatically
- **Type-safe** — Full type safety on every end (Kotlin ↔ TypeScript, Kotlin ↔ Swift)
- **HTTP + WebSocket** — Queries/mutations over HTTP POST, subscriptions over WebSocket
- **Sealed classes → Discriminated unions** — Kotlin sealed hierarchies become TypeScript unions with type guards and Swift enums with associated values
- **Subscriptions** — Kotlin `Flow` → TypeScript `AsyncIterable` / Swift `AsyncThrowingStream`, with automatic WebSocket management (reconnect, resubscribe, keep-alive)
- **Forward-compatible Swift** — Enum values and sealed subtypes added to the server later decode as `.unknown` instead of failing
- **Auth-agnostic** — Generic `@RpcContext` annotation lets you inject any request context (JWT, sessions, etc.)

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│  Your Project                                            │
│                                                          │
│  ┌─────────────┐    ┌──────────┐    ┌────────────┐       │
│  │ rpc-protocol│◄───│ your     │───►│ rpc-gen    │       │
│  │ (framework) │    │ schema   │    │ (codegen)  │       │
│  └──────┬──────┘    └──────────┘    └─────┬──────┘       │
│         │                                 │              │
│         ▼                          ┌──────┴───────┐      │
│  ┌─────────────┐                   ▼              ▼      │
│  │ Ktor Server │           ┌──────────────┐ ┌──────────┐ │
│  │ POST /rpc   │◄─────────►│ rpc.ts       │ │ Rpc.swift│ │
│  │ WS /rpc/ws  │ HTTP / WS │ (generated)  │ │ (gen.)   │ │
│  └─────────────┘           └──────────────┘ └──────────┘ │
│                            + client-runtime  + KtsRpc    │
└──────────────────────────────────────────────────────────┘
```

## Project Structure

```
kts-rpc/
├── rpc-protocol/       # Core: Ktor HTTP & WebSocket RPC handlers
├── rpc-gen/            # Core: TypeScript and Swift code generators
├── client-runtime/     # Core: TypeScript client (runtime.ts, rpc-proxy.ts, websocket-client.ts)
├── swift/              # Core: Swift client runtime, the KtsRpc package (Package.swift is at the root)
└── examples/
    ├── schema/         # Example: Kotlin RPC interfaces & DTOs
    ├── ktor-server/    # Example: Ktor backend with JWT auth
    ├── frontend/       # Example: Vite + TypeScript frontend
    └── swift/          # Example: the generated Swift client and its tests against the server
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
    implementation("com.github.planerist.ktsrpc:rpc-protocol:v0.2.0")
    implementation("com.github.planerist.ktsrpc:rpc-gen:v0.2.0")
}
```

**Or copy modules** — clone this repo and copy `rpc-protocol/` and `rpc-gen/` into your Gradle project.

The libraries work with Kotlin 2.3 and later.

### 2. Define Your RPC Schema

```kotlin
// your-schema/src/.../MyServiceRpc.kt
@Serializable
data class Greeting(val message: String)

interface GreeterServiceRpc : Rpc {
    suspend fun greet(name: String): Greeting
    suspend fun greetMe(@RpcContext context: MyContext): Greeting
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

A method's exception reaches the client as `{"error": message}`: the message of an `RpcError`,
`"Unauthorized"` for an `UnauthorizedException`, and `"Internal server error"` for anything else.

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

## Swift Client

The Swift client has two parts: the **KtsRpc** package (HTTP calls, the WebSocket subscription
protocol, date types) and a **generated file** with the `Rpc` types and the typed `RpcClient`
methods. It supports iOS 16+ and macOS 13+.

### 1. Add the Package

```swift
// Package.swift
dependencies: [
    .package(url: "https://github.com/planerist/ktsrpc.git", exact: "0.2.0"),
],
targets: [
    .target(name: "MyApi", dependencies: [.product(name: "KtsRpc", package: "ktsrpc")]),
]
```

In Xcode: *File → Add Package Dependencies…* with the same URL.

### 2. Generate Swift

```kotlin
tasks.register<JavaExec>("generateSwiftRpc") {
    dependsOn("assemble")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("net.planerist.ktsrpc.gen.SwiftRpcExporter")
    args = listOf(
        "--output", "../ios/MyApi/Sources/MyApi/Rpc.swift",
        "--context-annotation", "com.example.RpcContext",
        "com.example.GreeterServiceRpc",
        "com.example.TodoServiceRpc",
    )
}
```

| Option | Meaning |
|---|---|
| `--output <path>` | The Swift file to write (required) |
| `--context-annotation <class>` | The annotation of the server-side context parameter, which the client leaves out. A parameter named `context` is always left out, as in the TypeScript client |
| `--map <class>=<SwiftType>` | A Kotlin class with a hand-written Swift type, e.g. an id value class with its own Swift struct. Repeatable |
| `--runtime-module <name>` | The module to import the runtime from (default `KtsRpc`) |
| `--no-runtime-import` | The generated file is compiled into the runtime's own module |

### 3. Call the Server

```swift
import KtsRpc

// POST https://example.com/rpc, WebSocket wss://example.com/rpc/ws
var configuration = RpcConnection.Configuration(baseURL: URL(string: "https://example.com")!)
configuration.prepare = { request, kind in
    // Authenticate calls and the WebSocket upgrade the way your server expects.
    request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
}
let connection = RpcConnection(configuration: configuration)
let rpc = connection.client

let greeting = try await rpc.greet(name: "World")

// The stream ends when the server completes it, or when you stop iterating.
for try await todos in rpc.subscribeTodos() {
    render(todos)
}
```

- Errors are `RpcError`: `.server(message)` and `.unauthorized` for the method's exceptions,
  `.http(status:)` for a failed request.
- The server builds the context only when the WebSocket opens, so call
  `connection.subscriptions.reconnect()` after login and logout.
- Other routes: `RpcConnection.Configuration(endpoint:webSocketURL:)`. The endpoint
  `.perMethod(url)` posts to `url/<method>` with the arguments as the body, and
  `webSocketProtocols` sets the subprotocols to offer.
- A server with `encodeDefaults = false` leaves out fields that have their default value, so in
  Swift those fields are optional.

## Running the Example

```bash
# Backend
./gradlew examples:ktor-server:run

# Frontend (separate terminal)
cd examples/frontend
npm install
npm run dev

# Swift client against the running backend (separate terminal, macOS)
KTSRPC_EXAMPLE_SERVER=http://localhost:8080 swift test --filter ExampleRpcTests
```

Open http://localhost:5173 — login, try the greeter, add todos with live updates, join the chat room.

`swift test` alone runs the runtime's tests; the example's are skipped without a server.

## Supported Types

| Kotlin | TypeScript | Swift |
|---|---|---|
| `String`, `Int`, `Long`, `Double`, `Boolean` | `string`, `number`, `number`, `number`, `boolean` | `String`, `Int`, `Int`, `Double`, `Bool` |
| `List<T>`, `Set<T>` | `readonly T[]` | `[T]` |
| `Map<K, V>` | `Record<K, V>` | `[K: V]` |
| `T?` (nullable) | `T \| null` | `T?` |
| `@Serializable data class` | `interface` | `struct` (Codable) |
| `@Serializable enum class` | `type` (string union) | `enum` with `.unknown(String)` |
| `sealed interface/class` | Discriminated union + type guards | `enum` with associated values and `.unknown(type:)` |
| `value class` | — | Its underlying type |
| `OffsetDateTime`, `LocalDate`, `kotlin.time.Instant` | `string` | `Rpc.OffsetDateTime`, `Rpc.LocalDate`, `Rpc.Instant` |
| `Flow<T>` (return only) | `AsyncIterable<T>` | `AsyncThrowingStream<T, Error>` |

See [RELEASING.md](RELEASING.md) for how to publish a new version.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
