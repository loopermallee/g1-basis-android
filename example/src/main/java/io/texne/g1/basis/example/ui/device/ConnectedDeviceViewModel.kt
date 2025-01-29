package io.texne.g1.basis.example.ui.device

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.texne.g1.basis.example.model.Repository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class ConnectedDeviceViewModel @Inject constructor(
    private val repository: Repository
): ViewModel() {

    data class Page(
        val text: String
    )

    data class State(
        val pages: List<Page> = listOf()
    )

    private val writableState = MutableStateFlow<State>(State())
    val state = writableState.asStateFlow()

    fun setPages(pages: List<Page>) {
        writableState.value = state.value.copy(pages = pages)
    }

    fun showText() {
        // TODO
    }
}