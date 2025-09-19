package io.texne.g1.hub.todo

import java.util.LinkedHashMap
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class TodoRepository @Inject constructor(
    private val preferences: TodoPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private var activeBacking: List<TodoItem> = emptyList()
    private var archivedBacking: List<TodoItem> = emptyList()

    private val activeState = MutableStateFlow<List<TodoItem>>(emptyList())
    private val archivedState = MutableStateFlow<List<TodoItem>>(emptyList())

    val activeTasks: StateFlow<List<TodoItem>> = activeState.asStateFlow()
    val archivedTasks: StateFlow<List<TodoItem>> = archivedState.asStateFlow()

    private val initialLoad = scope.async { refreshInternal() }

    suspend fun refresh() {
        ensureInitialized()
        refreshInternal()
    }

    suspend fun addTask(shortText: String, fullText: String = shortText): TodoItem =
        withContext(Dispatchers.IO) {
            ensureInitialized()
            mutex.withLock {
                val sanitizedShortText = shortText.trim()
                val sanitizedFullText = fullText.ifBlank { sanitizedShortText }
                val sorted = activeBacking.sortedBy { it.position }.toMutableList()
                val insertionIndex = sorted.indexOfFirst { it.isDone }.takeIf { it >= 0 } ?: sorted.size
                val newItem = TodoItem(
                    id = UUID.randomUUID().toString(),
                    shortText = sanitizedShortText,
                    fullText = sanitizedFullText,
                    isDone = false,
                    archivedAt = null,
                    position = insertionIndex
                )
                sorted.add(insertionIndex, newItem)
                activeBacking = sorted.withNormalizedPositions()
                persistAndPublishLocked()
                newItem
            }
        }

    suspend fun markTaskDone(id: String, done: Boolean = true): TodoItem? =
        withContext(Dispatchers.IO) {
            ensureInitialized()
            mutex.withLock {
                val sorted = activeBacking.sortedBy { it.position }.toMutableList()
                val index = sorted.indexOfFirst { it.id == id }
                if (index == -1) {
                    return@withLock null
                }
                val item = sorted.removeAt(index)
                val updated = item.copy(isDone = done)
                if (done) {
                    sorted.add(updated)
                } else {
                    val insertionIndex = sorted.indexOfFirst { it.isDone }.takeIf { it >= 0 } ?: sorted.size
                    sorted.add(insertionIndex, updated)
                }
                activeBacking = sorted.withNormalizedPositions()
                persistAndPublishLocked()
                updated
            }
        }

    suspend fun toggleTask(id: String): TodoItem? = withContext(Dispatchers.IO) {
        ensureInitialized()
        val current = mutex.withLock {
            activeBacking.firstOrNull { it.id == id }
        }
        current?.let { markTaskDone(id, !it.isDone) }
    }

    suspend fun reorder(id: String, targetIndex: Int): List<TodoItem>? =
        withContext(Dispatchers.IO) {
            ensureInitialized()
            mutex.withLock {
                val sorted = activeBacking.sortedBy { it.position }.toMutableList()
                val totalUndone = sorted.count { !it.isDone }
                val currentIndex = sorted.indexOfFirst { it.id == id }
                if (currentIndex == -1) {
                    return@withLock null
                }
                val item = sorted[currentIndex]
                if (item.isDone) {
                    return@withLock null
                }
                sorted.removeAt(currentIndex)
                val newUndoneCount = totalUndone - 1
                val destination = targetIndex.coerceIn(0, newUndoneCount)
                sorted.add(destination, item)
                activeBacking = sorted.withNormalizedPositions()
                persistAndPublishLocked()
                activeState.value
            }
        }

    suspend fun archiveTask(id: String): TodoItem? = withContext(Dispatchers.IO) {
        ensureInitialized()
        mutex.withLock {
            val sorted = activeBacking.sortedBy { it.position }.toMutableList()
            val index = sorted.indexOfFirst { it.id == id }
            if (index == -1) {
                return@withLock null
            }
            val removed = sorted.removeAt(index)
            val archived = removed.copy(archivedAt = System.currentTimeMillis())
            activeBacking = sorted.withNormalizedPositions()
            archivedBacking = (archivedBacking + archived).sortedArchived()
            persistAndPublishLocked()
            archived
        }
    }

    suspend fun restoreTask(id: String): TodoItem? = withContext(Dispatchers.IO) {
        ensureInitialized()
        mutex.withLock {
            val index = archivedBacking.indexOfFirst { it.id == id }
            if (index == -1) {
                return@withLock null
            }
            val archivedItem = archivedBacking[index]
            val sortedActive = activeBacking.sortedBy { it.position }.toMutableList()
            val insertionIndex = sortedActive.indexOfFirst { it.isDone }.takeIf { it >= 0 } ?: sortedActive.size
            val restored = archivedItem.copy(
                isDone = false,
                archivedAt = null,
                position = insertionIndex
            )
            archivedBacking = archivedBacking.toMutableList().also { it.removeAt(index) }.sortedArchived()
            sortedActive.add(insertionIndex, restored)
            activeBacking = sortedActive.withNormalizedPositions()
            persistAndPublishLocked()
            restored
        }
    }

    suspend fun expandTask(id: String): TodoItem? = withContext(Dispatchers.IO) {
        ensureInitialized()
        mutex.withLock {
            activeBacking.firstOrNull { it.id == id } ?: archivedBacking.firstOrNull { it.id == id }
        }
    }

    private suspend fun refreshInternal() {
        val snapshot = preferences.load()
        mutex.withLock {
            activeBacking = arrangeActive(dedupe(snapshot.active))
            archivedBacking = dedupe(snapshot.archived).sortedArchived()
            publishLocked()
        }
    }

    private suspend fun persistAndPublishLocked() {
        preferences.persist(activeBacking, archivedBacking)
        publishLocked()
    }

    private fun publishLocked() {
        activeState.value = activeBacking.filter { !it.isDone && it.archivedAt == null }
        archivedState.value = archivedBacking
    }

    private fun arrangeActive(items: List<TodoItem>): List<TodoItem> {
        if (items.isEmpty()) {
            return emptyList()
        }
        val sanitized = items.map { item ->
            if (item.archivedAt != null) item.copy(archivedAt = null) else item
        }.sortedBy { it.position }
        val (undone, done) = sanitized.partition { !it.isDone }
        return (undone + done).withNormalizedPositions()
    }

    private fun List<TodoItem>.withNormalizedPositions(): List<TodoItem> =
        mapIndexed { index, item ->
            if (item.position != index) {
                item.copy(position = index)
            } else {
                item
            }
        }

    private fun List<TodoItem>.sortedArchived(): List<TodoItem> =
        sortedWith(
            compareByDescending<TodoItem> { it.archivedAt ?: Long.MIN_VALUE }
                .thenBy { it.position }
        )

    private fun dedupe(items: List<TodoItem>): List<TodoItem> {
        if (items.isEmpty()) return emptyList()
        val map = LinkedHashMap<String, TodoItem>(items.size)
        for (item in items) {
            if (!map.containsKey(item.id)) {
                map[item.id] = item
            }
        }
        return map.values.toList()
    }

    private suspend fun ensureInitialized() {
        if (!initialLoad.isCompleted) {
            initialLoad.await()
        }
    }
}
