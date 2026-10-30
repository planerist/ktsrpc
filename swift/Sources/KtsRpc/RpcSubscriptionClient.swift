import Foundation
import os

/// Subscriptions over one WebSocket speaking kts-rpc's variant of graphql-transport-ws
/// (`SubscriptionProtocolHandler` on the server):
///
///     → {"type":"connection_init"}                ← {"type":"connection_ack"}
///     → {"id":…,"type":"subscribe","payload":{"operationName":…,"query":{…}}}
///     ← {"type":"next","id":…,"payload":{"data":…}}
///     ← {"type":"error","id":…,"payload":{"error":"…"}}   ← {"type":"complete","id":…}
///     → {"id":…,"type":"complete"}                (unsubscribe)
///     → {"type":"ping"}                           ← {"type":"pong"}
///
/// The client pings `pingInterval` after the last pong (the server's keep-alive frames are pongs
/// too) and drops the socket when no pong comes within `pongTimeout`. It connects on the first subscription; after
/// a drop it reconnects with backoff and subscribes everything again (new ids, same arguments).
/// A subscription ends on the server's `error` or `complete`, or when its stream is no longer
/// consumed.
public actor RpcSubscriptionClient {
    public enum State: Sendable, Equatable {
        case disconnected
        case connecting
        case connected
    }

    public struct Timing: Sendable {
        public var ackTimeout: Duration = .seconds(15)
        public var pingInterval: Duration = .seconds(60)
        public var pongTimeout: Duration = .seconds(10)
        /// Delay before reconnect attempt `n` (from 0): min(2ⁿ s, 60 s) + 0.3–3 s.
        public var backoff: @Sendable (Int) -> Duration = { attempt in
            .seconds(min(pow(2, Double(attempt)), 60)) + .milliseconds(Int.random(in: 300...3000))
        }

        public init() {}
    }

    private struct Subscription {
        let method: String
        let argsJSON: String
        var wireId: String?
        var ended = false
        let deliver: @Sendable (Data) -> Void
        let end: @Sendable (Error?) -> Void
    }

    private let makeSocket: RpcWebSocketFactory
    private let timing: Timing
    private let log = Logger(subsystem: "net.planerist.ktsrpc", category: "websocket")

    private var state: State = .disconnected
    private var subscriptions: [UUID: Subscription] = [:]
    private var keysByWireId: [String: UUID] = [:]
    private var cancelledBeforeAdded: Set<UUID> = []
    private var stateObservers: [UUID: AsyncStream<State>.Continuation] = [:]

    private var socket: (any RpcWebSocket)?
    private var outbox: AsyncStream<String>.Continuation?
    /// Incremented per socket, so timers of an old socket do nothing.
    private var connection = 0
    private var loop: Task<Void, Never>?
    private var backoffSleep: Task<Void, Never>?
    private var timer: Task<Void, Never>?
    private var attempt = 0

    public init(timing: Timing = Timing(), makeSocket: @escaping RpcWebSocketFactory) {
        self.timing = timing
        self.makeSocket = makeSocket
    }

    // MARK: Public API

    public nonisolated func subscribe<Args: Encodable & Sendable, Element: Decodable & Sendable>(
        _ method: String, _ args: Args, returning: Element.Type
    ) -> AsyncThrowingStream<Element, Error> {
        AsyncThrowingStream { continuation in
            let argsJSON: String
            do {
                argsJSON = String(decoding: try JSONEncoder().encode(args), as: UTF8.self)
            } catch {
                continuation.finish(throwing: error)
                return
            }
            let key = UUID()
            let subscription = Subscription(
                method: method,
                argsJSON: argsJSON,
                deliver: { frame in
                    do {
                        let next = try JSONDecoder.rpc().decode(NextFrame<Element>.self, from: frame)
                        switch next.payload.result {
                        case .success(let value): continuation.yield(value)
                        case .failure(let error): continuation.finish(throwing: error)
                        }
                    } catch {
                        continuation.finish(throwing: error)
                    }
                },
                end: { error in
                    if let error { continuation.finish(throwing: error) } else { continuation.finish() }
                }
            )
            continuation.onTermination = { _ in
                Task { await self.remove(key) }
            }
            Task { await self.add(key, subscription) }
        }
    }

    public var currentState: State { state }

    /// The current state, then every change.
    public func stateUpdates() -> AsyncStream<State> {
        let (stream, continuation) = AsyncStream<State>.makeStream()
        let id = UUID()
        continuation.yield(state)
        stateObservers[id] = continuation
        continuation.onTermination = { _ in
            Task { await self.removeObserver(id) }
        }
        return stream
    }

    /// Drops the socket and connects again at once. Needed after login and logout: the server
    /// builds the context (the user) only when the socket opens.
    public func reconnect() {
        attempt = 0
        if let socket {
            socket.close()
        } else {
            backoffSleep?.cancel()
        }
    }

    /// Connects now if waiting to retry: the network came back or the app returned to the foreground.
    public func resume() {
        attempt = 0
        backoffSleep?.cancel()
    }

    /// Ends every subscription and stops connecting: the connection is being thrown away.
    public func shutdown() {
        loop?.cancel()
        loop = nil
        backoffSleep?.cancel()
        socket?.close()
        for key in subscriptions.keys { end(key, CancellationError()) }
    }

    // MARK: Subscriptions

    private func add(_ key: UUID, _ subscription: Subscription) {
        if cancelledBeforeAdded.remove(key) != nil { return }
        subscriptions[key] = subscription
        if state == .connected { sendSubscribe(key) }
        if loop == nil {
            loop = Task { await self.run() }
        }
    }

    private func remove(_ key: UUID) {
        guard let subscription = subscriptions.removeValue(forKey: key) else {
            cancelledBeforeAdded.insert(key)
            return
        }
        guard let wireId = subscription.wireId else { return }
        keysByWireId[wireId] = nil
        if !subscription.ended, state == .connected {
            send(Frame.complete(id: wireId))
        }
    }

    private func sendSubscribe(_ key: UUID) {
        guard var subscription = subscriptions[key], !subscription.ended else { return }
        let wireId = UUID().uuidString.lowercased()
        subscription.wireId = wireId
        subscriptions[key] = subscription
        keysByWireId[wireId] = key
        send(Frame.subscribe(id: wireId, method: subscription.method, argsJSON: subscription.argsJSON))
    }

    private func end(_ key: UUID, _ error: Error?) {
        guard var subscription = subscriptions[key], !subscription.ended else { return }
        subscription.ended = true
        subscriptions[key] = subscription
        if let wireId = subscription.wireId { keysByWireId[wireId] = nil }
        subscription.end(error)
    }

    // MARK: Connection

    private func run() async {
        while !Task.isCancelled {
            await connectOnce()
            guard !Task.isCancelled else { break }
            let delay = timing.backoff(attempt)
            attempt += 1
            log.info("Reconnecting in \(delay)")
            let sleep = Task { _ = try? await Task.sleep(for: delay) }
            backoffSleep = sleep
            await sleep.value
            backoffSleep = nil
        }
    }

    /// One socket from opening to closing.
    private func connectOnce() async {
        connection += 1
        let connection = self.connection
        setState(.connecting)

        var writer: Task<Void, Never>?
        do {
            let socket = try await makeSocket()
            self.socket = socket

            // One writer per socket keeps frames in the order they were sent.
            let (frames, outbox) = AsyncStream<String>.makeStream()
            self.outbox = outbox
            writer = Task {
                for await frame in frames {
                    do {
                        try await socket.send(frame)
                    } catch {
                        socket.close()
                        break
                    }
                }
            }

            send(Frame.connectionInit)
            schedule(after: timing.ackTimeout, connection) { client, connection in
                await client.close(connection, reason: "no connection_ack")
            }
            while true {
                handle(try await socket.receive(), connection)
            }
        } catch {
            log.info("WebSocket closed: \(error.localizedDescription)")
        }

        timer?.cancel()
        timer = nil
        outbox?.finish()
        outbox = nil
        writer?.cancel()
        socket?.close()
        socket = nil
        for key in subscriptions.keys { subscriptions[key]?.wireId = nil }
        keysByWireId = [:]
        setState(.disconnected)
    }

    private func handle(_ text: String, _ connection: Int) {
        let data = Data(text.utf8)
        guard let header = try? JSONDecoder.rpc().decode(FrameHeader.self, from: data) else {
            log.error("Unparsable frame: \(text.prefix(200))")
            return
        }
        let key = header.id.flatMap { keysByWireId[$0] }

        switch header.type {
        case "connection_ack":
            guard state == .connecting else { return }
            attempt = 0
            setState(.connected)
            scheduleKeepAlive(connection)
            for key in subscriptions.keys { sendSubscribe(key) }
        case "next":
            guard let key, let subscription = subscriptions[key], !subscription.ended else { return }
            subscription.deliver(data)
        case "error":
            let message = (try? JSONDecoder.rpc().decode(ErrorFrame.self, from: data))?.message
                ?? "Unknown subscription error"
            if let key {
                end(key, RpcError(serverMessage: message))
            } else {
                // About no particular subscription: the server could not parse a frame. Start over.
                log.error("Connection error: \(message)")
                close(connection, reason: message)
            }
        case "complete":
            if let key { end(key, nil) }
        case "ping":
            send(Frame.pong)
        case "pong":
            scheduleKeepAlive(connection)
        default:
            break
        }
    }

    private func send(_ frame: String) {
        outbox?.yield(frame)
    }

    private func close(_ connection: Int, reason: String) {
        guard connection == self.connection else { return }
        log.info("Closing WebSocket: \(reason)")
        socket?.close()
    }

    private func scheduleKeepAlive(_ connection: Int) {
        let pongTimeout = timing.pongTimeout
        schedule(after: timing.pingInterval, connection) { client, connection in
            await client.ping(connection)
            try await Task.sleep(for: pongTimeout)
            await client.close(connection, reason: "no pong")
        }
    }

    private func ping(_ connection: Int) {
        guard connection == self.connection else { return }
        send(Frame.ping)
    }

    /// Replaces the pending timer (ack timeout or keep-alive) of the socket.
    private func schedule(
        after delay: Duration, _ connection: Int,
        _ action: @escaping @Sendable (RpcSubscriptionClient, Int) async throws -> Void
    ) {
        timer?.cancel()
        timer = Task {
            do {
                try await Task.sleep(for: delay)
                try await action(self, connection)
            } catch {
                // Cancelled: a newer timer or the socket closed.
            }
        }
    }

    private func setState(_ newState: State) {
        guard state != newState else { return }
        state = newState
        for observer in stateObservers.values { observer.yield(newState) }
    }

    private func removeObserver(_ id: UUID) {
        stateObservers[id] = nil
    }
}

private enum Frame {
    static let connectionInit = #"{"type":"connection_init"}"#
    static let ping = #"{"type":"ping"}"#
    static let pong = #"{"type":"pong"}"#

    // A server with a strict Json rejects unknown envelope keys, so frames carry exactly type, id
    // and payload.
    static func subscribe(id: String, method: String, argsJSON: String) -> String {
        #"{"id":"\#(id)","type":"subscribe","payload":{"operationName":"\#(method)","query":\#(argsJSON)}}"#
    }

    static func complete(id: String) -> String {
        #"{"id":"\#(id)","type":"complete"}"#
    }
}

private struct FrameHeader: Decodable {
    let type: String
    let id: String?
}

private struct NextFrame<Element: Decodable>: Decodable {
    let payload: RpcResultBody<Element>
}

/// `payload` of an `error` frame: `{"error": message}`, or a bare string.
private struct ErrorFrame: Decodable {
    let message: String

    private enum CodingKeys: String, CodingKey {
        case payload, error
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        if let payload = try? c.nestedContainer(keyedBy: CodingKeys.self, forKey: .payload) {
            message = try payload.decode(String.self, forKey: .error)
        } else {
            message = try c.decode(String.self, forKey: .payload)
        }
    }
}
