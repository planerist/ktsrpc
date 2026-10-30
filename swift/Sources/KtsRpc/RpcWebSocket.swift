import Foundation

/// The socket the subscription client talks through; a protocol so tests can play the server.
public protocol RpcWebSocket: AnyObject, Sendable {
    func send(_ text: String) async throws
    /// The next text frame; throws once the socket is closed.
    func receive() async throws -> String
    func close()
}

public typealias RpcWebSocketFactory = @Sendable () async throws -> any RpcWebSocket

final class URLSessionRpcWebSocket: RpcWebSocket, @unchecked Sendable {
    private let task: URLSessionWebSocketTask

    init(task: URLSessionWebSocketTask, maximumMessageSize: Int) {
        self.task = task
        task.maximumMessageSize = maximumMessageSize
        task.resume()
    }

    func send(_ text: String) async throws {
        try await task.send(.string(text))
    }

    func receive() async throws -> String {
        switch try await task.receive() {
        case .string(let text): return text
        case .data(let data): return String(decoding: data, as: UTF8.self)
        @unknown default: throw RpcError.invalidResponse("Unknown WebSocket message kind")
        }
    }

    func close() {
        task.cancel(with: .normalClosure, reason: nil)
    }
}

extension URLSessionRpcWebSocket {
    /// Opens `url` offering `protocols`, with the session's cookies for `url` and whatever
    /// `prepare` adds: the server builds the context from the upgrade request only.
    static func factory(
        url: URL, protocols: [String], maximumMessageSize: Int, session: URLSession,
        prepare: RpcPrepareRequest?
    ) -> RpcWebSocketFactory {
        { [session] in
            var request = URLRequest(url: url)
            if !protocols.isEmpty {
                request.setValue(protocols.joined(separator: ", "), forHTTPHeaderField: "Sec-WebSocket-Protocol")
            }
            if let cookies = session.configuration.httpCookieStorage?.cookies(for: RpcConnection.httpURL(url)) {
                for (field, value) in HTTPCookie.requestHeaderFields(with: cookies) {
                    request.setValue(value, forHTTPHeaderField: field)
                }
            }
            try await prepare?(&request, .webSocket)
            return URLSessionRpcWebSocket(task: session.webSocketTask(with: request), maximumMessageSize: maximumMessageSize)
        }
    }
}
