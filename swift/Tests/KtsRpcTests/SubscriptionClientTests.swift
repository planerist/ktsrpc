import XCTest
@testable import KtsRpc

final class SubscriptionClientTests: XCTestCase {
    private let sockets = FakeSocketFactory()

    private func makeClient(
        pingInterval: Duration = .seconds(30), pongTimeout: Duration = .seconds(1)
    ) -> (RpcSubscriptionClient, RpcClient) {
        var timing = RpcSubscriptionClient.Timing()
        timing.ackTimeout = .seconds(1)
        timing.pingInterval = pingInterval
        timing.pongTimeout = pongTimeout
        timing.backoff = { _ in .milliseconds(10) }
        let subscriptions = RpcSubscriptionClient(timing: timing, makeSocket: sockets.make)
        return (subscriptions, RpcClient(transport: SubscriptionTransport(subscriptions: subscriptions)))
    }

    /// Waits for the next socket and completes the handshake as the server.
    private func acceptConnection() async throws -> FakeSocket {
        let socket = try await sockets.next()
        let initFrame = try await socket.nextSent()
        XCTAssertEqual(initFrame, #"{"type":"connection_init"}"#)
        socket.serverSends(#"{"type":"connection_ack"}"#)
        return socket
    }

    private struct SubscribeFrame {
        let id: String
        let method: String
        let query: [String: Any]
    }

    private func subscribeFrame(_ socket: FakeSocket) async throws -> SubscribeFrame {
        let frame = try jsonObject(try await socket.nextSent())
        XCTAssertEqual(frame["type"] as? String, "subscribe")
        XCTAssertEqual(Set(frame.keys), ["id", "type", "payload"])
        let payload = try XCTUnwrap(frame["payload"] as? [String: Any])
        return SubscribeFrame(
            id: try XCTUnwrap(frame["id"] as? String),
            method: try XCTUnwrap(payload["operationName"] as? String),
            query: try XCTUnwrap(payload["query"] as? [String: Any])
        )
    }

    private static let removed = #"{"type":"ItemRemoved","id":"a1","at":5}"#

    func testSubscribesAfterAckAndDeliversValuesUntilComplete() async throws {
        let (_, client) = makeClient()
        let events = Collector(client.events())

        let socket = try await acceptConnection()
        let subscribe = try await subscribeFrame(socket)
        XCTAssertEqual(subscribe.method, "events")
        XCTAssertEqual(Array(subscribe.query.keys), ["since"])

        socket.serverSends(#"{"type":"next","id":"\#(subscribe.id)","payload":{"data":\#(Self.removed)}}"#)
        let event = try await events.value()
        XCTAssertEqual(event, .itemRemoved(Rpc.ItemRemoved(id: "a1", at: 5)))

        socket.serverSends(#"{"type":"complete","id":"\#(subscribe.id)"}"#)
        guard case .finished = try await events.next() else { return XCTFail("expected the stream to finish") }
    }

    func testArgumentsGoIntoTheQuery() async throws {
        let (_, client) = makeClient()
        let events = Collector(client.events(since: Rpc.Instant(rawValue: "2026-10-30T09:12:44Z")))

        let socket = try await acceptConnection()
        let subscribe = try await subscribeFrame(socket)
        XCTAssertEqual(subscribe.method, "events")
        XCTAssertEqual(subscribe.query["since"] as? String, "2026-10-30T09:12:44Z")
        events.cancel()
    }

    func testServerErrorEndsTheSubscription() async throws {
        let (_, client) = makeClient()
        let events = Collector(client.events())

        let socket = try await acceptConnection()
        let subscribe = try await subscribeFrame(socket)
        socket.serverSends(#"{"type":"error","id":"\#(subscribe.id)","payload":{"error":"Unauthorized"}}"#)

        guard case .failed(let error) = try await events.next() else { return XCTFail("expected an error") }
        XCTAssertEqual(error as? RpcError, .unauthorized)
    }

    func testCancellingTheStreamUnsubscribes() async throws {
        let (_, client) = makeClient()
        let events = Collector(client.events())

        let socket = try await acceptConnection()
        let subscribe = try await subscribeFrame(socket)
        events.cancel()

        let complete = try await socket.nextSent()
        XCTAssertEqual(complete, #"{"id":"\#(subscribe.id)","type":"complete"}"#)
    }

    func testReconnectsAndSubscribesAgainWithANewId() async throws {
        let (subscriptions, client) = makeClient()
        let events = Collector(client.events())

        let first = try await acceptConnection()
        let before = try await subscribeFrame(first)
        first.close()

        let second = try await acceptConnection()
        let after = try await subscribeFrame(second)
        XCTAssertEqual(after.method, "events")
        XCTAssertNotEqual(after.id, before.id)

        // The same stream goes on receiving.
        second.serverSends(#"{"type":"next","id":"\#(after.id)","payload":{"data":\#(Self.removed)}}"#)
        _ = try await events.value()
        let state = await subscriptions.currentState
        XCTAssertEqual(state, .connected)
    }

    func testSubscriptionWhileConnectedIsSentAtOnce() async throws {
        let (_, client) = makeClient()
        let events = Collector(client.events())
        let socket = try await acceptConnection()
        _ = try await subscribeFrame(socket)

        let second = Collector(client.events(since: Rpc.Instant(rawValue: "2026-10-30T09:12:44Z")))
        let subscribe = try await subscribeFrame(socket)
        XCTAssertEqual(subscribe.query["since"] as? String, "2026-10-30T09:12:44Z")
        events.cancel()
        second.cancel()
    }

    func testAnswersServerPing() async throws {
        let (_, client) = makeClient()
        let events = Collector(client.events())
        let socket = try await acceptConnection()
        _ = try await subscribeFrame(socket)

        socket.serverSends(#"{"type":"ping"}"#)
        let pong = try await socket.nextSent()
        XCTAssertEqual(pong, #"{"type":"pong"}"#)
        events.cancel()
    }

    func testPingsAndReconnectsWithoutPong() async throws {
        let (_, client) = makeClient(pingInterval: .milliseconds(50), pongTimeout: .milliseconds(50))
        let events = Collector(client.events())
        let socket = try await acceptConnection()
        _ = try await subscribeFrame(socket)

        let ping = try await socket.nextSent()
        XCTAssertEqual(ping, #"{"type":"ping"}"#)
        // No pong: the client drops the socket and opens a new one.
        _ = try await acceptConnection()
        XCTAssertTrue(socket.isClosed)
        events.cancel()
    }
}
