import XCTest
@testable import KtsRpc

final class HTTPCallTests: XCTestCase {
    private final class Server: @unchecked Sendable {
        var status = 200
        var body = #"{"data":{}}"#
        private(set) var requests: [URLRequest] = []

        func load(_ request: URLRequest) -> (Data, URLResponse) {
            requests.append(request)
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
            return (Data(body.utf8), response)
        }
    }

    private static let base = URL(string: "http://localhost:8080")!

    private func client(
        _ server: Server, endpoint: RpcHTTPEndpoint = .single(base.appendingPathComponent("rpc")),
        prepare: RpcPrepareRequest? = nil
    ) -> RpcClient {
        let caller = RpcHTTPCaller(endpoint: endpoint, prepare: prepare) { request in
            server.load(request)
        }
        return RpcClient(transport: StubHTTPTransport(caller: caller))
    }

    private func body(_ request: URLRequest?) throws -> [String: Any] {
        try jsonObject(String(decoding: try XCTUnwrap(request?.httpBody), as: UTF8.self))
    }

    func testCallPostsTheMethodAndNamedArgumentsWithExplicitNulls() async throws {
        let server = Server()
        server.body = #"{"data":[]}"#

        let items = try await client(server).items(priority: nil)

        XCTAssertEqual(items, [])
        let request = try XCTUnwrap(server.requests.first)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.absoluteString, "http://localhost:8080/rpc")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")
        let body = try body(request)
        XCTAssertEqual(body["operationName"] as? String, "items")
        let query = try XCTUnwrap(body["query"] as? [String: Any])
        XCTAssertEqual(Array(query.keys), ["priority"])
        XCTAssertTrue(query["priority"] is NSNull)
    }

    func testPerMethodEndpointPostsTheArgumentsAlone() async throws {
        let server = Server()
        server.body = #"{"data":null}"#

        let item = try await client(server, endpoint: .perMethod(Self.base.appendingPathComponent("api"))).item(id: "a1")

        XCTAssertNil(item)
        XCTAssertEqual(server.requests.first?.url?.absoluteString, "http://localhost:8080/api/item")
        XCTAssertEqual(try body(server.requests.first) as NSDictionary, ["id": "a1"])
    }

    func testMethodWithoutParametersSendsAnEmptyObject() async throws {
        let server = Server()
        server.body = #"{"data":"pong"}"#

        let pong = try await client(server).ping()

        XCTAssertEqual(pong, "pong")
        XCTAssertEqual(try body(server.requests.first)["query"] as? NSDictionary, [:])
    }

    func testUnitResult() async throws {
        let server = Server()
        try await client(server).save(item: Rpc.Item(id: "a1", title: "Milk", counts: [:], default: false))

        let query = try XCTUnwrap(try body(server.requests.first)["query"] as? [String: Any])
        let item = try XCTUnwrap(query["item"] as? [String: Any])
        XCTAssertEqual(item["title"] as? String, "Milk")
        // Left out: the server's default applies. Sent as null: the field is nullable.
        XCTAssertNil(item["priority"])
        XCTAssertTrue(item["note"] is NSNull)
    }

    func testPrepareAuthenticatesTheCall() async throws {
        let server = Server()
        server.body = #"{"data":"pong"}"#
        let client = client(server) { request, kind in
            XCTAssertEqual(kind, .call(method: "ping"))
            request.setValue("Bearer t0ken", forHTTPHeaderField: "Authorization")
        }

        _ = try await client.ping()

        XCTAssertEqual(server.requests.first?.value(forHTTPHeaderField: "Authorization"), "Bearer t0ken")
    }

    func testErrorsAreHTTP200Bodies() async throws {
        let server = Server()
        server.body = #"{"error":"Unauthorized"}"#
        do {
            _ = try await client(server).ping()
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? RpcError, .unauthorized)
        }

        server.body = #"{"error":"Item not found"}"#
        do {
            _ = try await client(server).item(id: "a1")
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? RpcError, .server("Item not found"))
        }
    }

    func testMissingArgumentIsHTTP500() async throws {
        let server = Server()
        server.status = 500
        do {
            _ = try await client(server).ping()
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? RpcError, .http(status: 500))
        }
    }

    func testExampleServerRoutes() {
        let configuration = RpcConnection.Configuration(baseURL: URL(string: "https://example.com")!)
        XCTAssertEqual(configuration.endpoint, .single(URL(string: "https://example.com/rpc")!))
        XCTAssertEqual(configuration.webSocketURL.absoluteString, "wss://example.com/rpc/ws")
        XCTAssertEqual(
            RpcConnection.webSocketURL(URL(string: "http://localhost:8080/rpc/ws")!).absoluteString,
            "ws://localhost:8080/rpc/ws"
        )
    }
}
