@file:OptIn(ExperimentalTime::class)

package net.planerist.ktsrpc.gen.tests.swift

import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// The schema of the Swift package's tests (swift/Tests/KtsRpcTests/Generated/TestRpc.swift):
// one of each thing the Swift generator handles.

@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class Ctx

/** Filled in by the server; not serializable, never in the client. */
class Session(val userId: Long?)

@JvmInline
@Serializable
value class ItemId(val value: String)

@Serializable
enum class Priority {
    Low,
    Normal,

    @SerialName("urgent")
    Urgent,

    /** Clashes with the case Swift enums get for values they don't know. */
    Unknown,
}

@Serializable
data class Item(
    val id: ItemId,
    val title: String,
    val priority: Priority = Priority.Normal,
    val note: String? = null,
    @SerialName("due_date")
    val due: LocalDate? = null,
    val tags: List<String> = emptyList(),
    val counts: Map<String, Int>,
    val default: Boolean,
)

@Serializable
sealed interface Event {
    @Serializable
    data class Added(val item: Item) : Event

    @Serializable
    @SerialName("ItemRemoved")
    data class Removed(val id: ItemId, val at: Long) : Event

    @Serializable
    data object Reset : Event
}

interface ItemsRpc {
    suspend fun item(@Ctx session: Session, id: ItemId): Item?

    /** Left out by name, as in the TypeScript client. */
    suspend fun items(context: Session, priority: Priority?): List<Item>

    suspend fun save(@Ctx session: Session, item: Item)

    suspend fun events(@Ctx session: Session, since: Instant?): Flow<Event>

    suspend fun ping(): String
}
