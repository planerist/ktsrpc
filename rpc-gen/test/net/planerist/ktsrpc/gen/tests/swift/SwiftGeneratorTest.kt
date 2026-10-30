package net.planerist.ktsrpc.gen.tests.swift

import net.planerist.ktsrpc.gen.swiftGenerator.SwiftGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class SwiftGeneratorTest {
    private val text = SwiftGenerator(listOf(ItemsRpc::class), contextAnnotation = Ctx::class).text

    /**
     * The Swift package's tests compile and decode this file, so it must be what the generator
     * writes now. To regenerate it: `KTSRPC_UPDATE_SWIFT=1 ./gradlew :rpc-gen:test --rerun`.
     */
    @Test
    fun checkedInSwiftIsUpToDate() {
        val file = File("../swift/Tests/KtsRpcTests/Generated/TestRpc.swift")
        if (System.getenv("KTSRPC_UPDATE_SWIFT") != null) file.writeText(text)
        assertEquals(file.readText(), text, "${file.path} is stale: KTSRPC_UPDATE_SWIFT=1 ./gradlew :rpc-gen:test --rerun")
    }

    @Test
    fun contextParametersAreLeftOut() {
        assertTrue("public func item(id: String) async throws -> Rpc.Item?" in text)
        assertTrue("public func items(priority: Rpc.Priority? = nil) async throws -> [Rpc.Item]" in text)
        assertTrue("public func ping() async throws -> String" in text)
        assertFalse("Session" in text)
    }

    @Test
    fun flowsAreStreamsAndUnitIsEmpty() {
        assertTrue("public func events(since: Rpc.Instant? = nil) -> AsyncThrowingStream<Rpc.Event, Error>" in text)
        assertTrue("_ = try await transport.call(\"save\", Rpc.Args.Save(item: item), returning: RpcEmpty.self)" in text)
    }

    @Test
    fun fieldsFollowTheSerialDescriptor() {
        // A value class is its property; @SerialName renames the key, not the Swift name.
        assertTrue("public var id: String\n" in text)
        assertTrue("case due = \"due_date\"" in text)
        assertTrue("public var `default`: Bool" in text)
        // A default makes a field optional; left out when nil, while a nullable one is sent as null.
        assertTrue("public var priority: Rpc.Priority?" in text)
        assertTrue("try c.encodeIfPresent(self.priority, forKey: .priority)" in text)
        assertTrue("try c.encode(self.note, forKey: .note)" in text)
    }

    @Test
    fun enumsAndSealedClassesKeepUnknownValues() {
        assertTrue("case \"urgent\": self = .urgent" in text)
        assertTrue("case unknownValue" in text)
        assertTrue("case \"ItemRemoved\": self = .itemRemoved(try Rpc.ItemRemoved(from: decoder))" in text)
        assertTrue("case \"net.planerist.ktsrpc.gen.tests.swift.Event.Reset\": self = .reset(try Rpc.Reset(from: decoder))" in text)
        assertTrue("case unknown(type: String)" in text)
    }
}
