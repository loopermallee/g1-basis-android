package io.texne.g1.hub.todo

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import io.texne.g1.hub.model.Repository

@Singleton
class TodoRepository @Inject constructor(
    private val serviceRepository: Repository
) {
    private val itemsFlow = MutableStateFlow<List<TodoItem>>(emptyList())
    val items: StateFlow<List<TodoItem>> = itemsFlow.asStateFlow()

    init {
        if (itemsFlow.value.isEmpty()) {
            itemsFlow.value = listOf(
                TodoItem(
                    id = 1L,
                    summary = "Outline the onboarding walkthrough",
                    fullText = "Draft the full onboarding walkthrough for new G1 wearers, including safety tips and quick-start instructions.",
                ),
                TodoItem(
                    id = 2L,
                    summary = "Test the BLE reconnection flow",
                    fullText = "Verify that the background reconnection flow gracefully recovers after toggling Bluetooth and document any timing issues.",
                ),
                TodoItem(
                    id = 3L,
                    summary = "Schedule user research interviews",
                    fullText = "Coordinate with the research team to schedule three interviews with early adopters and capture their availability windows.",
                ),
                TodoItem(
                    id = 4L,
                    summary = "Prepare demo HUD copy",
                    fullText = "Write the long-form copy used in the live demo, including alternate states for error conditions and fallback prompts.",
                )
            )
        }
    }

    fun replaceAll(newItems: List<TodoItem>) {
        itemsFlow.value = newItems
    }

    fun toggleCompletion(id: Long) {
        itemsFlow.update { items ->
            items.map { item ->
                if (item.id == id) {
                    item.copy(isCompleted = !item.isCompleted)
                } else {
                    item
                }
            }
        }
    }

    fun moveTaskUp(id: Long) {
        itemsFlow.update { items ->
            val index = items.indexOfFirst { it.id == id }
            if (index <= 0) return@update items
            val mutable = items.toMutableList()
            val task = mutable.removeAt(index)
            mutable.add(index - 1, task)
            mutable.toList()
        }
    }

    fun moveTaskDown(id: Long) {
        itemsFlow.update { items ->
            val index = items.indexOfFirst { it.id == id }
            if (index == -1 || index >= items.lastIndex) return@update items
            val mutable = items.toMutableList()
            val task = mutable.removeAt(index)
            mutable.add(index + 1, task)
            mutable.toList()
        }
    }

    suspend fun displayHudPage(lines: List<String>, animate: Boolean = true): Boolean {
        val pages = listOf(padLines(lines))
        val holdMillis = if (animate) DEFAULT_PAGE_HOLD_MILLIS else null
        return serviceRepository.displayCenteredOnConnectedGlasses(pages, holdMillis)
    }

    suspend fun stopHudDisplay(): Boolean =
        serviceRepository.stopDisplayingOnConnectedGlasses()

    private fun padLines(lines: List<String>): List<String> {
        val mutable = lines.toMutableList()
        while (mutable.size < TodoHudFormatter.LINES_PER_PAGE) {
            mutable += ""
        }
        return mutable.take(TodoHudFormatter.LINES_PER_PAGE)
    }

    companion object {
        private const val DEFAULT_PAGE_HOLD_MILLIS = 4_000L
    }
}
