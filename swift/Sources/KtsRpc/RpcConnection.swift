import Foundation

/// A kts-rpc server: calls over HTTP and subscriptions over one WebSocket, through one
/// `URLSession`, so a session cookie the server sets on a call reaches the WebSocket as well.
public final class RpcConnection: RpcTransport {
    public struct Configuration: Sendable {
        public var endpoint: RpcHTTPEndpoint
        public var webSocketURL: URL
        /// Subprotocols offered on the WebSocket upgrade (`Sec-WebSocket-Protocol`); none by default.
        public var webSocketProtocols: [String] = []
        /// Authenticates a call or the WebSocket upgrade, e.g. with a bearer token header, or a
        /// query parameter on the upgrade.
        public var prepare: RpcPrepareRequest?
        public var callTimeout: TimeInterval = 15
        /// Big initial states exceed URLSession's default of 1 MB.
        public var maximumMessageSize = 64 * 1024 * 1024
        public var timing = RpcSubscriptionClient.Timing()

        public init(endpoint: RpcHTTPEndpoint, webSocketURL: URL) {
            self.endpoint = endpoint
            self.webSocketURL = webSocketURL
        }

        /// The routes of the example server: `POST <baseURL>/rpc` and the WebSocket `<baseURL>/rpc/ws`.
        public init(baseURL: URL) {
            self.init(
                endpoint: .single(baseURL.appendingPathComponent("rpc")),
                webSocketURL: RpcConnection.webSocketURL(baseURL.appendingPathComponent("rpc/ws"))
            )
        }
    }

    public let configuration: Configuration
    public let session: URLSession
    public let subscriptions: RpcSubscriptionClient
    private let http: RpcHTTPCaller

    public init(configuration: Configuration, sessionConfiguration: URLSessionConfiguration = .default) {
        self.configuration = configuration
        sessionConfiguration.httpCookieAcceptPolicy = .always
        sessionConfiguration.httpShouldSetCookies = true
        let session = URLSession(configuration: sessionConfiguration)
        self.session = session
        http = RpcHTTPCaller(
            endpoint: configuration.endpoint, timeout: configuration.callTimeout, prepare: configuration.prepare
        ) { request in
            try await session.data(for: request)
        }
        subscriptions = RpcSubscriptionClient(
            timing: configuration.timing,
            makeSocket: URLSessionRpcWebSocket.factory(
                url: configuration.webSocketURL, protocols: configuration.webSocketProtocols,
                maximumMessageSize: configuration.maximumMessageSize, session: session,
                prepare: configuration.prepare
            )
        )
    }

    public var client: RpcClient { RpcClient(transport: self) }

    public func call<Args: Encodable & Sendable, Result: Decodable & Sendable>(
        _ method: String, _ args: Args, returning: Result.Type
    ) async throws -> Result {
        try await http.call(method, args, returning: returning)
    }

    public func subscribe<Args: Encodable & Sendable, Element: Decodable & Sendable>(
        _ method: String, _ args: Args, returning: Element.Type
    ) -> AsyncThrowingStream<Element, Error> {
        subscriptions.subscribe(method, args, returning: returning)
    }

    /// The cookies the server has set.
    public var cookies: [HTTPCookie] {
        session.configuration.httpCookieStorage?.cookies(for: configuration.endpoint.url) ?? []
    }

    public func clearCookies() {
        for cookie in cookies {
            session.configuration.httpCookieStorage?.deleteCookie(cookie)
        }
    }

    /// `url` with the WebSocket scheme: `ws` for `http`, `wss` for `https`.
    public static func webSocketURL(_ url: URL) -> URL {
        var components = URLComponents(url: url, resolvingAgainstBaseURL: false)!
        components.scheme = url.scheme == "https" || url.scheme == "wss" ? "wss" : "ws"
        return components.url!
    }

    /// `url` with the HTTP scheme, to look up its cookies.
    static func httpURL(_ url: URL) -> URL {
        var components = URLComponents(url: url, resolvingAgainstBaseURL: false)!
        components.scheme = url.scheme == "wss" || url.scheme == "https" ? "https" : "http"
        return components.url!
    }
}
