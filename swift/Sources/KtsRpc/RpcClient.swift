import Foundation

/// Namespace of the wire types generated from the Kotlin RPC interfaces (SwiftRpcExporter).
public enum Rpc {}

/// Carries calls and subscriptions to the server.
///
/// `call` is an HTTP POST with the arguments as a JSON object keyed by parameter name;
/// `subscribe` is a subscription over the shared WebSocket.
public protocol RpcTransport: Sendable {
    func call<Args: Encodable & Sendable, Result: Decodable & Sendable>(
        _ method: String, _ args: Args, returning: Result.Type
    ) async throws -> Result

    func subscribe<Args: Encodable & Sendable, Element: Decodable & Sendable>(
        _ method: String, _ args: Args, returning: Element.Type
    ) -> AsyncThrowingStream<Element, Error>
}

/// Typed client of the server. Its methods are generated (SwiftRpcExporter).
public struct RpcClient: Sendable {
    public let transport: any RpcTransport

    public init(transport: any RpcTransport) {
        self.transport = transport
    }
}

public enum RpcError: Error, Equatable, Sendable {
    /// The method answered `{"error": message}`: it threw an `RpcError` with this message. Other
    /// exceptions read "Internal server error".
    case server(String)
    /// The method threw `UnauthorizedException` (`{"error": "Unauthorized"}`).
    case unauthorized
    /// A non-200 HTTP status (a missing or undecodable argument, an unknown method, a failed
    /// authentication).
    case http(status: Int)
    case invalidResponse(String)
    /// The WebSocket closed while the subscription was being established.
    case connectionLost

    public init(serverMessage: String) {
        self = serverMessage == "Unauthorized" ? .unauthorized : .server(serverMessage)
    }
}

/// Result of methods that return `Unit` on the server (`{"data": {}}`).
public struct RpcEmpty: Decodable, Sendable {
    public init(from decoder: Decoder) throws {}
}

/// Arguments of methods without parameters: an empty JSON object.
public struct RpcNoArgs: Encodable, Sendable {
    public init() {}

    public func encode(to encoder: Encoder) throws {
        _ = encoder.container(keyedBy: RpcAnyKey.self)
    }
}

struct RpcAnyKey: CodingKey {
    var stringValue: String
    var intValue: Int? { nil }

    init(_ string: String) { stringValue = string }
    init?(stringValue: String) { self.stringValue = stringValue }
    init?(intValue: Int) { nil }
}

extension JSONDecoder {
    /// The server's JSON may be lenient (pretty-printed, special floats allowed).
    static func rpc() -> JSONDecoder {
        let decoder = JSONDecoder()
        decoder.allowsJSON5 = true
        return decoder
    }
}
