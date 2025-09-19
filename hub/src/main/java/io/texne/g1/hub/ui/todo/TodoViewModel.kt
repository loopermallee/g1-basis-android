package io.texne.g1.hub.ui.todo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.texne.g1.hub.model.Repository
import io.texne.g1.hub.todo.TodoHudFormatter
import io.texne.g1.hub.todo.TodoHudFormatter.DisplayMode
import io.texne.g1.hub.todo.TodoItem
import io.texne.g1.hub.todo.TodoRepository
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class TodoViewModel @Inject constructor(
    private val todoRepository: TodoRepository,
    private val serviceRepository: Repository
) : ViewModel() {

    data class State(
        val tasks: List<TodoItem> = emptyList(),
        val displayMode: DisplayMode = DisplayMode.SUMMARY,
        val expandedTaskId: String? = null,
        val pageIndex: Int = 0,
        val pageCount: Int = 1,
        val hudError: Boolean = false
    )

    sealed interface AiCommand {
        data object NextPage : AiCommand
        data class ExpandTask(val position: Int) : AiCommand
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var hudPages: List<List<String>> = emptyList()
    private var pendingRefresh: Job? = null

    init {
        viewModelScope.launch {
            todoRepository.refresh()
            todoRepository.activeTasks.collectLatest { items ->
                val ordered = items.sortedBy { it.position }
                val sanitizedExpanded = _state.value.expandedTaskId?.takeIf { id ->
                    ordered.any { it.id == id }
                }
                _state.update { state ->
                    state.copy(
                        tasks = ordered,
                        expandedTaskId = sanitizedExpanded
                    )
                }
                rebuildHudPages(resetIndex = true)
            }
        }
    }

    fun toggleTask(id: String) {
        viewModelScope.launch { todoRepository.toggleTask(id) }
    }

    fun moveTaskUp(id: String) {
        viewModelScope.launch {
            val undone = _state.value.tasks.filter { !it.isDone }
            val index = undone.indexOfFirst { it.id == id }
            if (index > 0) {
                todoRepository.reorder(id, index - 1)
            }
        }
    }

    fun moveTaskDown(id: String) {
        viewModelScope.launch {
            val undone = _state.value.tasks.filter { !it.isDone }
            val index = undone.indexOfFirst { it.id == id }
            if (index != -1 && index < undone.lastIndex) {
                todoRepository.reorder(id, index + 1)
            }
        }
    }

    fun setDisplayMode(mode: DisplayMode) {
        if (mode == _state.value.displayMode) {
            return
        }
        _state.update { it.copy(displayMode = mode, expandedTaskId = null) }
        rebuildHudPages(resetIndex = true)
    }

    fun expandTask(position: Int) {
        val zeroBased = position - 1
        val target = _state.value.tasks.getOrNull(zeroBased) ?: return
        if (_state.value.expandedTaskId == target.id) {
            return
        }
        _state.update { it.copy(expandedTaskId = target.id) }
        rebuildHudPages(resetIndex = true)
    }

    fun collapseExpanded() {
        if (_state.value.expandedTaskId == null) {
            return
        }
        _state.update { it.copy(expandedTaskId = null) }
        rebuildHudPages(resetIndex = true)
    }

    fun goToNextPage() {
        val current = _state.value
        val lastIndex = current.pageCount - 1
        if (current.pageIndex >= lastIndex) {
            return
        }
        updatePageIndex(current.pageIndex + 1)
    }

    fun goToPreviousPage() {
        val current = _state.value
        if (current.pageIndex <= 0) {
            return
        }
        updatePageIndex(current.pageIndex - 1)
    }

    fun handleAiCommand(command: AiCommand) {
        when (command) {
            AiCommand.NextPage -> goToNextPage()
            is AiCommand.ExpandTask -> expandTask(command.position)
        }
    }

    fun clearHudError() {
        if (_state.value.hudError) {
            _state.update { it.copy(hudError = false) }
        }
    }

    private fun rebuildHudPages(resetIndex: Boolean) {
        val current = _state.value
        val result = TodoHudFormatter.format(
            tasks = current.tasks,
            displayMode = current.displayMode,
            expandedTaskId = current.expandedTaskId
        )
        val pages = result.pages.ifEmpty { listOf(listOf("")) }
        val desiredIndex = if (resetIndex) 0 else current.pageIndex.coerceIn(0, pages.lastIndex)
        val pagesChanged = pages != hudPages
        hudPages = pages

        _state.update {
            it.copy(
                pageIndex = desiredIndex,
                pageCount = pages.size.coerceAtLeast(1)
            )
        }

        pendingRefresh?.cancel()
        pendingRefresh = viewModelScope.launch {
            if (pagesChanged) {
                sendPages(pages, desiredIndex)
            } else {
                displayPage(pages, desiredIndex)
            }
        }
    }

    private fun updatePageIndex(newIndex: Int) {
        val pages = hudPages
        if (pages.isEmpty() || newIndex !in pages.indices) {
            return
        }
        _state.update { it.copy(pageIndex = newIndex) }
        pendingRefresh?.cancel()
        pendingRefresh = viewModelScope.launch {
            displayPage(pages, newIndex)
        }
    }

    private suspend fun sendPages(pages: List<List<String>>, pageIndex: Int) {
        if (pages.isEmpty()) {
            return
        }
        val success = serviceRepository.displayCenteredOnConnectedGlasses(pages, holdMillis = null)
        val finalSuccess = if (success && pageIndex != 0 && pages.size > 1) {
            serviceRepository.displayCenteredPageOnConnectedGlasses(pages, pageIndex)
        } else {
            success
        }
        updateHudError(!finalSuccess)
    }

    private suspend fun displayPage(pages: List<List<String>>, pageIndex: Int) {
        if (pages.isEmpty()) {
            return
        }
        val success = serviceRepository.displayCenteredPageOnConnectedGlasses(pages, pageIndex)
        updateHudError(!success)
    }

    private fun updateHudError(failed: Boolean) {
        _state.update { it.copy(hudError = failed) }
    }
}
