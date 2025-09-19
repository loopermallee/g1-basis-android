package io.texne.g1.hub.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.texne.g1.hub.ai.ChatGptRepository
import io.texne.g1.hub.assistant.AssistantActivationGesture
import io.texne.g1.hub.assistant.AssistantPreferences
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val chatRepository: ChatGptRepository,
    private val assistantPreferences: AssistantPreferences
) : ViewModel() {

    data class State(
        val currentKey: String = "",
        val inputKey: String = "",
        val isSaving: Boolean = false,
        val activationGesture: AssistantActivationGesture = AssistantActivationGesture.OFF,
        val message: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            chatRepository.observeApiKey().collectLatest { key ->
                _state.update { state ->
                    state.copy(
                        currentKey = key.orEmpty(),
                        inputKey = key.orEmpty()
                    )
                }
            }
        }

        viewModelScope.launch {
            assistantPreferences.observeActivationGesture().collectLatest { gesture ->
                _state.update { state ->
                    state.copy(activationGesture = gesture)
                }
            }
        }
    }

    fun onInputChanged(value: String) {
        _state.update { it.copy(inputKey = value) }
    }

    fun saveKey() {
        val trimmed = _state.value.inputKey.trim()
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, message = null) }
            chatRepository.updateApiKey(trimmed.ifBlank { null })
            val confirmation = if (trimmed.isBlank()) {
                "API key cleared."
            } else {
                "API key saved."
            }
            _state.update { it.copy(isSaving = false, message = confirmation) }
        }
    }

    fun clearKey() {
        viewModelScope.launch {
            _state.update { it.copy(inputKey = "", isSaving = true, message = null) }
            chatRepository.updateApiKey(null)
            _state.update { it.copy(isSaving = false, message = "Stored key removed.") }
        }
    }

    fun setActivationGesture(gesture: AssistantActivationGesture) {
        if (_state.value.activationGesture == gesture) {
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(activationGesture = gesture) }
            assistantPreferences.setActivationGesture(gesture)
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }
}
