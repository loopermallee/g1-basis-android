package io.texne.g1.hub.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.texne.g1.hub.settings.HudSettingsRepository
import io.texne.g1.hub.settings.HudWidget
import io.texne.g1.hub.settings.HudWidgetDefaults
import io.texne.g1.hub.settings.HudWidgetType
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class HudSettingsViewModel @Inject constructor(
    private val repository: HudSettingsRepository
) : ViewModel() {

    data class State(
        val widgets: List<HudWidget> = HudWidgetDefaults.widgets
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.widgets.collectLatest { widgets ->
                _state.update { it.copy(widgets = widgets) }
            }
        }
    }

    fun setEnabled(type: HudWidgetType, enabled: Boolean) {
        viewModelScope.launch {
            repository.setEnabled(type, enabled)
        }
    }

    fun moveUp(type: HudWidgetType) {
        move(type, -1)
    }

    fun moveDown(type: HudWidgetType) {
        move(type, 1)
    }

    private fun move(type: HudWidgetType, delta: Int) {
        viewModelScope.launch {
            repository.move(type, delta)
        }
    }
}
