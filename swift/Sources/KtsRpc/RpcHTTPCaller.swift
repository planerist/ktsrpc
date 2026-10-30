import Foundation

/// Where calls are posted.
public enum RpcHTTPEndpoint: Sendable, Equatable {
    /// `POST url` with `{"operationName": method, "query": arguments}`, as the TypeScript client
    /// and the example server do.
    case single(URL)
    /// `POST url/<method>` with the arguments as the body.
    case perMethod(URL)

    public var url: URL {
        switch self {
        case .single(let url), .perMethod(let url): return url
        }
    }
}

/// What a request is for, so `RpcConnection.Configuration.prepare` can authenticate each the way
/// the server expects (a header on calls, a query parameter on the WebSocket upgrade…).
public enum RpcRequestKind: Sendable, Equatable {
    case call(method: String)
    case webSocket
}

public typealias RpcPrepareRequest = @Sendable (inout URLRequest, RpcRequestKind) async throws -> Void

/// Calls over HTTP. The answer is HTTP 200 with `{"data": …}` or `{"error": "…"}`.
struct RpcHTTPCaller: Sendable {
    typealias Load = @Sendable (URLRequest) async throws -> (Data, URLResponse)

    let endpoint: RpcHTTPEndpoint
    var timeout: TimeInterval = 15
    var prepare: RpcPrepareRequest?
    let load: Load

    func call<Args: Encodable, Result: Decodable>(
        _ method: String, _ args: Args, returning: Result.Type
    ) async throws -> Result {
        var request: URLRequest
        switch endpoint {
        case .single(let url):
            request = URLRequest(url: url, timeoutInterval: timeout)
            request.httpBody = try JSONEncoder().encode(Envelope(operationName: method, query: args))
        case .perMethod(let url):
            request = URLRequest(url: url.appendingPathComponent(method), timeoutInterval: timeout)
            request.httpBody = try JSONEncoder().encode(args)
        }
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        try await prepare?(&request, .call(method: method))

        let (data, response) = try await load(request)
        guard let http = response as? HTTPURLResponse else {
            throw RpcError.invalidResponse("\(method): not an HTTP response")
        }
        guard http.statusCode == 200 else { throw RpcError.http(status: http.statusCode) }
        return try JSONDecoder.rpc().decode(RpcResultBody<Result>.self, from: data).result.get()
    }

    private struct Envelope<Query: Encodable>: Encodable {
        let operationName: String
        let query: Query
    }
}

/// `{"data": value}` or `{"error": message}`: the body of an HTTP answer and the payload of a
/// subscription's `next` and `error` frames.
struct RpcResultBody<Value: Decodable>: Decodable {
    let result: Result<Value, RpcError>

    private enum CodingKeys: String, CodingKey {
        case data, error
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        if let message = try c.decodeIfPresent(String.self, forKey: .error) {
            result = .failure(RpcError(serverMessage: message))
        } else {
            result = .success(try c.decode(Value.self, forKey: .data))
        }
    }
}
