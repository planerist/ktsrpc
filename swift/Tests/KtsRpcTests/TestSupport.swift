import Foundation
@testable import KtsRpc

struct TimeoutError: Error {}

/// Runs `body`, failing after `seconds` instead of hanging the test run.
func withTimeout<T: Sendable>(
    _ seconds: Double = 2, _ body: @escaping @Sendable () async throws -> T?
) async throws -> T {
    try await withThrowingTaskGroup(of: T?.self) { group in
        group.addTask { try await body() }
        group.addTask {
            try await Task.sleep(for: .seconds(seconds))
            throw TimeoutError()
        }
        defer { group.cancelAll() }
        guard let result = try await group.next(), let value = result else { throw TimeoutError() }
        return value
    }
}

/// A WebSocket whose other end is the test, playing the server.
final class FakeSocket: RpcWebSocket, @unchecked Sendable {
    private let incoming: AsyncThrowingStream<String, Error>.Continuation
    private var incomingFrames: AsyncThrowingStream<String, Error>.AsyncIterator
    private let sent: AsyncStream<String>.Continuation
    private var sentFrames: AsyncStream<String>.AsyncIterator
    private(set) var isClosed = false

    init() {
        let (incoming, incomingContinuation) = AsyncThrowingStream<String, Error>.makeStream()
        self.incoming = incomingContinuation
        incomingFrames = incoming.makeAsyncIterator()
        let (sent, sentContinuation) = AsyncStream<String>.makeStream()
        self.sent = sentContinuation
        sentFrames = sent.makeAsyncIterator()
    }

    func send(_ text: String) async throws {
        if isClosed { throw URLError(.networkConnectionLost) }
        sent.yield(text)
    }

    func receive() async throws -> String {
        guard let text = try await incomingFrames.next() else { throw URLError(.networkConnectionLost) }
        return text
    }

    func close() {
        isClosed = true
        incoming.finish(throwing: URLError(.networkConnectionLost))
    }

    // The server's side

    func serverSends(_ text: String) {
        incoming.yield(text)
    }

    func nextSent() async throws -> String {
        try await withTimeout { await self.sentFrames.next() }
    }
}

/// Hands the client a new `FakeSocket` per connection and lets the test wait for it.
final class FakeSocketFactory: @unchecked Sendable {
    private let created: AsyncStream<FakeSocket>.Continuation
    private var sockets: AsyncStream<FakeSocket>.AsyncIterator

    init() {
        let (stream, continuation) = AsyncStream<FakeSocket>.makeStream()
        created = continuation
        sockets = stream.makeAsyncIterator()
    }

    var make: RpcWebSocketFactory {
        { [created] in
            let socket = FakeSocket()
            created.yield(socket)
            return socket
        }
    }

    func next() async throws -> FakeSocket {
        try await withTimeout { await self.sockets.next() }
    }
}

/// Consumes a subscription stream so a test can await its events with a timeout.
final class Collector<Element: Sendable>: @unchecked Sendable {
    enum Event: @unchecked Sendable {
        case value(Element)
        case finished
        case failed(Error)
    }

    private var events: AsyncStream<Event>.AsyncIterator
    private let task: Task<Void, Never>

    init(_ stream: AsyncThrowingStream<Element, Error>) {
        let (events, continuation) = AsyncStream<Event>.makeStream()
        self.events = events.makeAsyncIterator()
        task = Task {
            do {
                for try await value in stream { continuation.yield(.value(value)) }
                continuation.yield(.finished)
            } catch {
                continuation.yield(.failed(error))
            }
            continuation.finish()
        }
    }

    func next(timeout: Double = 2) async throws -> Event {
        try await withTimeout(timeout) { await self.events.next() }
    }

    func value(timeout: Double = 2) async throws -> Element {
        switch try await next(timeout: timeout) {
        case .value(let value): return value
        case .failed(let error): throw error
        case .finished: throw TimeoutError()
        }
    }

    func cancel() {
        task.cancel()
    }
}

/// A transport that answers calls with canned HTTP responses through `RpcHTTPCaller`.
struct StubHTTPTransport: RpcTransport {
    let caller: RpcHTTPCaller

    func call<Args: Encodable & Sendable, Result: Decodable & Sendable>(
        _ method: String, _ args: Args, returning: Result.Type
    ) async throws -> Result {
        try await caller.call(method, args, returning: returning)
    }

    func subscribe<Args: Encodable & Sendable, Element: Decodable & Sendable>(
        _ method: String, _ args: Args, returning: Element.Type
    ) -> AsyncThrowingStream<Element, Error> {
        AsyncThrowingStream { $0.finish(throwing: RpcError.connectionLost) }
    }
}

/// A transport whose subscriptions go through `RpcSubscriptionClient`.
struct SubscriptionTransport: RpcTransport {
    let subscriptions: RpcSubscriptionClient

    func call<Args: Encodable & Sendable, Result: Decodable & Sendable>(
        _ method: String, _ args: Args, returning: Result.Type
    ) async throws -> Result {
        throw RpcError.connectionLost
    }

    func subscribe<Args: Encodable & Sendable, Element: Decodable & Sendable>(
        _ method: String, _ args: Args, returning: Element.Type
    ) -> AsyncThrowingStream<Element, Error> {
        subscriptions.subscribe(method, args, returning: returning)
    }
}

func jsonObject(_ text: String) throws -> [String: Any] {
    guard let object = try JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any] else {
        throw TimeoutError()
    }
    return object
}
