package io.texne.g1.hub.ui.todo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.texne.g1.hub.todo.TodoItem
import io.texne.g1.hub.todo.TodoRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class TodoViewModel @Inject constructor(
    private val repository: TodoRepository
) : ViewModel() {

    private val _state = MutableStateFlow(TodoState())
    val state: StateFlow<TodoState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                repository.activeTasks,
                repository.archivedTasks
            ) { active, archived ->
                active to archived
            }.collect { (active, archived) ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        activeTasks = active,
                        archivedTasks = archived
                    )
                }
            }
        }

        viewModelScope.launch {
            runCatching { repository.refresh() }
                .onFailure {
                    _state.update { state ->
                        state.copy(
                            isLoading = false,
                            errorMessage = "Unable to load tasks."
                        )
                    }
                }
        }
    }

    fun addTask(text: String) {
        val sanitized = text.trim()
        if (sanitized.isEmpty()) return

        viewModelScope.launch {
            runCatching { repository.addTask(sanitized) }
                .onFailure {
                    _state.update { state ->
                        state.copy(errorMessage = "Unable to add task.")
                    }
                }
        }
    }

    fun toggleTask(id: String) {
        viewModelScope.launch {
            runCatching { repository.toggleTask(id) }
                .onFailure {
                    _state.update { state ->
                        state.copy(errorMessage = "Unable to update task.")
                    }
                }
        }
    }

    fun archiveTask(id: String) {
        viewModelScope.launch {
            runCatching { repository.archiveTask(id) }
                .onFailure {
                    _state.update { state ->
                        state.copy(errorMessage = "Unable to archive task.")
                    }
                }
        }
    }

    fun restoreTask(id: String) {
        viewModelScope.launch {
            runCatching { repository.restoreTask(id) }
                .onFailure {
                    _state.update { state ->
                        state.copy(errorMessage = "Unable to restore task.")
                    }
                }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(errorMessage = null) }
    }
}

data class TodoState(
    val isLoading: Boolean = true,
    val activeTasks: List<TodoItem> = emptyList(),
    val archivedTasks: List<TodoItem> = emptyList(),
    val errorMessage: String? = null,
)
