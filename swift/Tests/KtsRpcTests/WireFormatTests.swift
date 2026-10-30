import XCTest
@testable import KtsRpc

/// Decoding and encoding of generated types (Generated/TestRpc.swift, from rpc-gen's
/// SwiftTestSchema.kt) against JSON as kotlinx.serialization writes it.
final class WireFormatTests: XCTestCase {
    private func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try JSONDecoder.rpc().decode(type, from: Data(json.utf8))
    }

    private func encodeObject<T: Encodable>(_ value: T) throws -> [String: Any] {
        let data = try JSONEncoder().encode(value)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]) as? [String: Any])
    }

    func testFullItem() throws {
        let item = try decode(Rpc.Item.self, #"""
            {"id":"a1","title":"Milk","priority":"urgent","note":null,"due_date":"2026-10-31",
             "tags":["home"],"counts":{"bought":2},"default":true}
            """#)
        XCTAssertEqual(item.id, "a1")
        XCTAssertEqual(item.priority, .urgent)
        XCTAssertNil(item.note)
        XCTAssertEqual(item.due, Rpc.LocalDate(year: 2026, month: 10, day: 31))
        XCTAssertEqual(item.tags, ["home"])
        XCTAssertEqual(item.counts, ["bought": 2])
        XCTAssertTrue(item.default)
    }

    func testFieldsWithDefaultsMayBeMissing() throws {
        // A server with encodeDefaults = false leaves them out.
        let item = try decode(Rpc.Item.self, #"{"id":"a1","title":"Milk","counts":{},"default":false}"#)
        XCTAssertNil(item.priority)
        XCTAssertNil(item.tags)

        let object = try encodeObject(item)
        XCTAssertEqual(object.keys.sorted(), ["counts", "default", "due_date", "id", "note", "title"])
        XCTAssertTrue(object["note"] is NSNull)
    }

    func testSealedSubtypes() throws {
        let events = try decode([Rpc.Event].self, #"""
            [{"type":"net.planerist.ktsrpc.gen.tests.swift.Event.Added","item":{"id":"a1","title":"Milk","counts":{},"default":false}},
             {"type":"ItemRemoved","id":"a1","at":1798000000000},
             {"type":"net.planerist.ktsrpc.gen.tests.swift.Event.Reset"}]
            """#)
        guard events.count == 3, case .added(let added) = events[0] else { return XCTFail("\(events)") }
        XCTAssertEqual(added.item.title, "Milk")
        XCTAssertEqual(events[1], .itemRemoved(Rpc.ItemRemoved(id: "a1", at: 1_798_000_000_000)))
        XCTAssertEqual(events[2], .reset(Rpc.Reset()))
    }

    func testSealedValueEncodesItsDiscriminator() throws {
        let removed = try encodeObject(Rpc.Event.itemRemoved(.init(id: "a1", at: 5)))
        XCTAssertEqual(removed["type"] as? String, "ItemRemoved")
        XCTAssertEqual(removed["id"] as? String, "a1")

        let reset = try encodeObject(Rpc.Event.reset(.init()))
        XCTAssertEqual(reset.keys.sorted(), ["type"])
    }

    func testUnknownSubtypeAndEnumValueDoNotFail() throws {
        let event = try decode(Rpc.Event.self, #"{"type":"ItemArchived","id":"a1"}"#)
        XCTAssertEqual(event, .unknown(type: "ItemArchived"))
        XCTAssertThrowsError(try JSONEncoder().encode(event))

        let priorities = try decode([Rpc.Priority].self, #"["Low","Unknown","Someday"]"#)
        XCTAssertEqual(priorities, [.low, .unknownValue, .unknown("Someday")])
        XCTAssertEqual(priorities[2].rawValue, "Someday")
        XCTAssertEqual(Rpc.Priority.knownCases, [.low, .normal, .urgent, .unknownValue])
    }

    func testArgumentsSendNilAsExplicitNull() throws {
        let object = try encodeObject(Rpc.Args.Items(priority: nil))
        XCTAssertEqual(object.keys.sorted(), ["priority"])
        XCTAssertTrue(object["priority"] is NSNull)
    }
}
