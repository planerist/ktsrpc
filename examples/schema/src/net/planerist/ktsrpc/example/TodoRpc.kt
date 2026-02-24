package net.planerist.ktsrpc.example

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import net.planerist.ktsrpc.protocol.Rpc

/**
 * A to-do item.
 */
@Serializable
data class TodoItem(
    val id: String,
    val title: String,
    val completed: Boolean
)

/**
 * Example RPC service demonstrating CRUD operations with real-time subscriptions.
 *
 * Usage:
 *   rpc.addTodo("Buy milk")                         // mutation
 *   val todos = rpc.listTodos()                      // query
 *   for await (const todos of rpc.subscribeTodos())  // subscription (TS)
 */
interface TodoServiceRpc : Rpc {
    /**
     * Returns all to-do items for the authenticated user.
     * Demonstrates: query returning a list.
     */
    suspend fun listTodos(@RpcContext context: RpcContextData): List<TodoItem>

    /**
     * Creates a new to-do item.
     * Demonstrates: mutation with parameters.
     */
    suspend fun addTodo(@RpcContext context: RpcContextData, title: String): TodoItem

    /**
     * Toggles the completed state of a to-do item.
     */
    suspend fun toggleTodo(@RpcContext context: RpcContextData, id: String): TodoItem

    /**
     * Deletes a to-do item by ID.
     */
    suspend fun deleteTodo(@RpcContext context: RpcContextData, id: String)

    /**
     * Subscribes to real-time to-do list updates.
     * The initial emission contains the current snapshot; subsequent emissions are pushed on every change.
     *
     * Demonstrates: Flow-based WebSocket subscription.
     */
    suspend fun subscribeTodos(@RpcContext context: RpcContextData): Flow<List<TodoItem>>
}
