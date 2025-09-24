package io.texne.g1.hub.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.texne.g1.basis.core.BleLogger
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class DiagnosticsViewModel @Inject constructor() : ViewModel() {
    companion object {
        private const val MAX_LINES = 200
    }

    data class State(
        val lines: List<String> = emptyList(),
        val maxLines: Int = MAX_LINES
    )

    val state: StateFlow<State> = BleLogger.lines
        .map { entries ->
            val recent = if (entries.size > MAX_LINES) {
                entries.takeLast(MAX_LINES)
            } else {
                entries
            }
            State(lines = recent, maxLines = MAX_LINES)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = State()
        )
}
