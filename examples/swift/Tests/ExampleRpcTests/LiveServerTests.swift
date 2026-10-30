import ExampleRpc
import KtsRpc
import XCTest

/// The generated client against the example server:
///
///     ./gradlew examples:ktor-server:run
///     KTSRPC_EXAMPLE_SERVER=http://localhost:8080 swift test --filter ExampleRpcTests
final class LiveServerTests: XCTestCase {
    private var server: URL!

    override func setUpWithError() throws {
        let address = ProcessInfo.processInfo.environment["KTSRPC_EXAMPLE_SERVER"]
        try XCTSkipIf(address == nil, "KTSRPC_EXAMPLE_SERVER is not set")
        server = try XCTUnwrap(URL(string: address!))
    }

    /// Logs in as the example's demo user alice (userId 1) with the server's `/auth/login`.
    private func token() async throws -> String {
        struct Token: Decodable { let token: String }
        var request = URLRequest(url: server.appendingPathComponent("auth/login"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data(#"{"username":"alice","password":"password1"}"#.utf8)
        let (data, _) = try await URLSession.shared.data(for: request)
        return try JSONDecoder().decode(Token.self, from: data).token
    }

    /// The example server reads the JWT from the Authorization header on calls and from the
    /// `token` query parameter on the WebSocket upgrade.
    private func connection(token: String?) -> RpcConnection {
        var configuration = RpcConnection.Configuration(baseURL: server)
        if let token {
            configuration.prepare = { request, kind in
                switch kind {
                case .call:
                    request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
                case .webSocket:
                    var components = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!
                    components.queryItems = [URLQueryItem(name: "token", value: token)]
                    request.url = components.url
                }
            }
        }
        return RpcConnection(configuration: configuration, sessionConfiguration: .ephemeral)
    }

    private struct Timeout: Error {}

    /// The first value of `stream` that satisfies `condition`, within 5 seconds. The example's
    /// StateFlow sends the current list again right after the subscription's first value.
    private func firstUpdate<T: Sendable>(
        _ stream: AsyncThrowingStream<T, Error>, where condition: @escaping @Sendable (T) -> Bool
    ) async throws -> T {
        try await withThrowingTaskGroup(of: T?.self) { group in
            group.addTask {
                for try await value in stream where condition(value) { return value }
                return nil
            }
            group.addTask {
                try await Task.sleep(for: .seconds(5))
                return nil
            }
            defer { group.cancelAll() }
            guard let value = try await group.next() ?? nil else { throw Timeout() }
            return value
        }
    }

    func testGreetings() async throws {
        let greeting = try await connection(token: nil).client.greet(name: "World")
        XCTAssertEqual(greeting.message, "Hello, World!")

        let anonymous = try await connection(token: nil).client.greetMe()
        XCTAssertEqual(anonymous.message, "Hello, Anonymous!")

        let alice = try await connection(token: try await token()).client.greetMe()
        XCTAssertEqual(alice.message, "Hello, User #1!")
    }

    func testTodosNeedALogin() async throws {
        do {
            _ = try await connection(token: nil).client.listTodos()
            XCTFail("expected an error")
        } catch {
            // The method throws IllegalStateException: only an RpcError's message reaches the client.
            XCTAssertEqual(error as? RpcError, .server("Internal server error"))
        }
    }

    func testTodoSubscriptionSeesChanges() async throws {
        let connection = connection(token: try await token())
        let client = connection.client
        let title = "Buy milk \(UUID().uuidString.prefix(8))"
        let todos = client.subscribeTodos()
        _ = try await firstUpdate(todos) { _ in true }  // The current list.

        let added = try await client.addTodo(title: title)
        _ = try await firstUpdate(todos) { $0.contains(added) }

        _ = try await client.toggleTodo(id: added.id)
        _ = try await firstUpdate(todos) { $0.first { $0.id == added.id }?.completed == true }

        try await client.deleteTodo(id: added.id)
        _ = try await firstUpdate(todos) { !$0.contains { $0.id == added.id } }
        await connection.subscriptions.shutdown()
    }

    func testChatEventsAreASealedHierarchy() async throws {
        let connection = connection(token: try await token())
        let client = connection.client
        let room = "swift-\(UUID().uuidString.prefix(8))"

        var events = client.subscribeChatEvents(room: room).makeAsyncIterator()
        guard case .userJoined(let joined) = try await events.next() else { return XCTFail("expected UserJoined") }
        XCTAssertEqual(joined.userId, "1")

        try await client.sendMessage(room: room, text: "Hello from Swift")
        guard case .chatMessage(let message) = try await events.next() else { return XCTFail("expected ChatMessage") }
        XCTAssertEqual(message.text, "Hello from Swift")
        XCTAssertEqual(message.userId, "1")
        await connection.subscriptions.shutdown()
    }
}
