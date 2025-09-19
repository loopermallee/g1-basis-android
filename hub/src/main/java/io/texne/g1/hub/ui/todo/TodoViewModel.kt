package io.texne.g1.hub.ui.todo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.texne.g1.hub.todo.TodoItem
import io.texne.g1.hub.todo.TodoRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class TodoViewModel @Inject constructor(
    private val repository: TodoRepository
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val errorMessage: String? = null,
        val expandedTask: TodoItem? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val activeTasks: StateFlow<List<TodoItem>> = repository.activeTasks.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    val archivedTasks: StateFlow<List<TodoItem>> = repository.archivedTasks.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    init {
        refresh(initial = true)
    }

    fun refresh() {
        refresh(initial = false)
    }

    private fun refresh(initial: Boolean) {
        viewModelScope.launch {
            if (initial) {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            } else {
                _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
            }

            val result = runCatching { repository.refresh() }
            _uiState.update { state ->
                if (result.isSuccess) {
                    state.copy(isLoading = false, isRefreshing = false, errorMessage = null)
                } else {
                    val message = result.exceptionOrNull()?.message ?: "Unable to refresh tasks"
                    state.copy(isLoading = false, isRefreshing = false, errorMessage = message)
                }
            }
        }
    }

    fun addTask(shortText: String, fullText: String = shortText) {
        viewModelScope.launch {
            val sanitizedShort = shortText.trim()
            if (sanitizedShort.isEmpty()) {
                return@launch
            }
            val sanitizedFull = fullText.trim().ifBlank { sanitizedShort }
            val result = runCatching { repository.addTask(sanitizedShort, sanitizedFull) }
            result.onFailure { error ->
                val message = error.message ?: "Unable to add task"
                _uiState.update { it.copy(errorMessage = message) }
            }
        }
    }

    fun toggleTask(id: String) {
        viewModelScope.launch {
            val result = runCatching { repository.toggleTask(id) }
            result.onFailure { error ->
                val message = error.message ?: "Unable to update task"
                _uiState.update { it.copy(errorMessage = message) }
            }.onSuccess { item ->
                if (item == null) {
                    _uiState.update { it.copy(errorMessage = "Unable to update task") }
                }
            }
        }
    }

    fun archiveTask(id: String) {
        viewModelScope.launch {
            val result = runCatching { repository.archiveTask(id) }
            result.onFailure { error ->
                val message = error.message ?: "Unable to archive task"
                _uiState.update { it.copy(errorMessage = message) }
            }.onSuccess { item ->
                if (item == null) {
                    _uiState.update { it.copy(errorMessage = "Unable to archive task") }
                }
            }
        }
    }

    fun restoreTask(id: String) {
        viewModelScope.launch {
            val result = runCatching { repository.restoreTask(id) }
            result.onFailure { error ->
                val message = error.message ?: "Unable to restore task"
                _uiState.update { it.copy(errorMessage = message) }
            }.onSuccess { item ->
                if (item == null) {
                    _uiState.update { it.copy(errorMessage = "Unable to restore task") }
                }
            }
        }
    }

    fun expandTask(id: String) {
        viewModelScope.launch {
            val result = runCatching { repository.expandTask(id) }
            result.onSuccess { item ->
                if (item != null) {
                    _uiState.update { it.copy(expandedTask = item) }
                } else {
                    _uiState.update { it.copy(errorMessage = "Task not found") }
                }
            }.onFailure { error ->
                val message = error.message ?: "Unable to load task details"
                _uiState.update { it.copy(errorMessage = message) }
            }
        }
    }

    fun clearExpandedTask() {
        _uiState.update { it.copy(expandedTask = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
