package net.planerist.ktsrpc.example.server

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import net.planerist.ktsrpc.example.RpcContextData
import net.planerist.ktsrpc.example.TodoItem
import net.planerist.ktsrpc.example.TodoServiceRpc
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory Todo service demonstrating CRUD + real-time subscriptions.
 *
 * Uses MutableStateFlow to push updates to all subscribers whenever the list changes.
 */
class TodoService : TodoServiceRpc {

    // In-memory store: userId → list of todos
    private val store = ConcurrentHashMap<Long, MutableList<TodoItem>>()
    private val flows = ConcurrentHashMap<Long, MutableStateFlow<List<TodoItem>>>()

    private fun todosFor(userId: Long): MutableList<TodoItem> =
        store.getOrPut(userId) { mutableListOf() }

    private fun flowFor(userId: Long): MutableStateFlow<List<TodoItem>> =
        flows.getOrPut(userId) { MutableStateFlow(todosFor(userId).toList()) }

    private fun notifyChange(userId: Long) {
        flowFor(userId).value = todosFor(userId).toList()
    }

    override suspend fun listTodos(context: RpcContextData): List<TodoItem> {
        val userId = context.userId ?: error("Authentication required")
        return todosFor(userId).toList()
    }

    override suspend fun addTodo(context: RpcContextData, title: String): TodoItem {
        val userId = context.userId ?: error("Authentication required")
        val item = TodoItem(
            id = UUID.randomUUID().toString().take(8),
            title = title,
            completed = false
        )
        todosFor(userId).add(item)
        notifyChange(userId)
        return item
    }

    override suspend fun toggleTodo(context: RpcContextData, id: String): TodoItem {
        val userId = context.userId ?: error("Authentication required")
        val todos = todosFor(userId)
        val index = todos.indexOfFirst { it.id == id }
        if (index == -1) error("Todo not found: $id")
        val updated = todos[index].copy(completed = !todos[index].completed)
        todos[index] = updated
        notifyChange(userId)
        return updated
    }

    override suspend fun deleteTodo(context: RpcContextData, id: String) {
        val userId = context.userId ?: error("Authentication required")
        todosFor(userId).removeAll { it.id == id }
        notifyChange(userId)
    }

    override suspend fun subscribeTodos(context: RpcContextData): Flow<List<TodoItem>> {
        val userId = context.userId ?: error("Authentication required")
        return flowFor(userId).onStart { emit(todosFor(userId).toList()) }
    }
}
