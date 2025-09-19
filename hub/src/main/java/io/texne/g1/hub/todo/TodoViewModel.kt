package io.texne.g1.hub.todo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val repository: TodoRepository
) : ViewModel() {

    data class State(
        val tasks: List<TodoItem> = emptyList(),
        val displayMode: TodoDisplayMode = TodoDisplayMode.SUMMARY,
        val currentPageIndex: Int = 0,
        val pageCount: Int = 0,
        val expandedTaskId: Long? = null,
        val summaryHighlightRange: IntRange? = null
    ) {
        val isSummaryMode: Boolean get() = displayMode == TodoDisplayMode.SUMMARY
        val isFullMode: Boolean get() = displayMode == TodoDisplayMode.FULL
        val showingSingleTask: Boolean get() = isFullMode && expandedTaskId != null
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var cachedPages: List<List<String>> = emptyList()
    private var hudJob: Job? = null

    init {
        viewModelScope.launch {
            repository.items.collectLatest { items ->
                applyChanges(tasks = items, animate = true)
            }
        }
    }

    fun onTaskToggle(id: Long) {
        repository.toggleCompletion(id)
    }

    fun onMoveTaskUp(id: Long) {
        repository.moveTaskUp(id)
    }

    fun onMoveTaskDown(id: Long) {
        repository.moveTaskDown(id)
    }

    fun onTapHud() {
        val state = _state.value
        if (state.displayMode != TodoDisplayMode.SUMMARY) {
            onDisplayModeSelected(TodoDisplayMode.SUMMARY)
            return
        }
        val range = state.summaryHighlightRange ?: return
        val targetIndex = range.first
        val task = state.tasks.getOrNull(targetIndex) ?: return
        repository.toggleCompletion(task.id)
    }

    fun onSwipeNextPage() {
        setPage(_state.value.currentPageIndex + 1)
    }

    fun onSwipePreviousPage() {
        setPage(_state.value.currentPageIndex - 1)
    }

    fun onAiCommandNextPage() {
        onSwipeNextPage()
    }

    fun onAiCommandExpandTask(taskNumber: Int) {
        if (taskNumber <= 0) return
        val index = taskNumber - 1
        val task = _state.value.tasks.getOrNull(index) ?: return
        applyChanges(
            displayMode = TodoDisplayMode.FULL,
            expandedTaskId = task.id,
            pageIndex = 0,
            animate = true
        )
    }

    fun onDisplayModeSelected(mode: TodoDisplayMode) {
        val state = _state.value
        if (state.displayMode == mode && (mode != TodoDisplayMode.FULL || state.expandedTaskId == null)) {
            return
        }
        val expandedId = if (mode == TodoDisplayMode.FULL) state.expandedTaskId else null
        applyChanges(
            displayMode = mode,
            expandedTaskId = expandedId,
            pageIndex = 0,
            animate = true
        )
    }

    fun onShowFullList() {
        applyChanges(
            displayMode = TodoDisplayMode.FULL,
            expandedTaskId = null,
            pageIndex = 0,
            animate = true
        )
    }

    fun onShowSummary() {
        applyChanges(
            displayMode = TodoDisplayMode.SUMMARY,
            expandedTaskId = null,
            pageIndex = 0,
            animate = true
        )
    }

    fun setPage(index: Int, animate: Boolean = true) {
        if (cachedPages.isEmpty()) return
        val safeIndex = index.coerceIn(0, cachedPages.lastIndex)
        if (safeIndex == _state.value.currentPageIndex) return
        applyChanges(pageIndex = safeIndex, animate = animate)
    }

    private fun applyChanges(
        tasks: List<TodoItem>? = null,
        displayMode: TodoDisplayMode? = null,
        expandedTaskId: Long? = null,
        pageIndex: Int? = null,
        animate: Boolean
    ) {
        val current = _state.value
        val resolvedTasks = tasks ?: current.tasks
        val resolvedMode = displayMode ?: current.displayMode
        val resolvedExpandedId = if (resolvedMode == TodoDisplayMode.FULL) {
            expandedTaskId ?: current.expandedTaskId
        } else {
            null
        }

        val pages = TodoHudFormatter.format(resolvedTasks, resolvedMode, resolvedExpandedId)
        cachedPages = pages
        val newPageIndex = when {
            pages.isEmpty() -> 0
            pageIndex != null -> pageIndex.coerceIn(0, pages.lastIndex)
            current.currentPageIndex > pages.lastIndex -> pages.lastIndex
            else -> current.currentPageIndex.coerceIn(0, pages.lastIndex)
        }

        val highlightRange = computeSummaryRange(resolvedMode, resolvedTasks, newPageIndex)

        _state.update {
            it.copy(
                tasks = resolvedTasks,
                displayMode = resolvedMode,
                expandedTaskId = resolvedExpandedId,
                currentPageIndex = newPageIndex,
                pageCount = pages.size,
                summaryHighlightRange = highlightRange
            )
        }

        refreshHud(pages, newPageIndex, animate)
    }

    private fun computeSummaryRange(
        mode: TodoDisplayMode,
        tasks: List<TodoItem>,
        pageIndex: Int
    ): IntRange? {
        if (mode != TodoDisplayMode.SUMMARY || tasks.isEmpty()) {
            return null
        }
        val start = pageIndex * TodoHudFormatter.LINES_PER_PAGE
        if (start >= tasks.size) {
            return null
        }
        val end = minOf(tasks.lastIndex, start + TodoHudFormatter.LINES_PER_PAGE - 1)
        return start..end
    }

    private fun refreshHud(pages: List<List<String>>, pageIndex: Int, animate: Boolean) {
        hudJob?.cancel()
        if (pages.isEmpty()) {
            hudJob = viewModelScope.launch {
                repository.stopHudDisplay()
            }
            return
        }
        val lines = pages[pageIndex]
        hudJob = viewModelScope.launch {
            repository.displayHudPage(lines, animate)
        }
    }
}
