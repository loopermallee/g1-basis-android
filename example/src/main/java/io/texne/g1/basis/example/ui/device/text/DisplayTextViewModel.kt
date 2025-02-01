package io.texne.g1.basis.example.ui.device.text

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.texne.g1.basis.example.model.Repository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DisplayTextViewModel @Inject constructor(
    private val repository: Repository
): ViewModel() {

    private var glasses: String = ""

    data class State(
        val sending: Boolean = false,
        val pages: List<List<String>> = listOf(listOf()),
        val error: Boolean = false
    )

    private val writableState = MutableStateFlow<State>(State())
    val state = writableState.asStateFlow()

    fun setGlassesId(id: String) {
        glasses = id
    }

    fun setPages(pages: List<List<String>>) {
        writableState.value = state.value.copy(pages = pages)
    }

    fun showText() {
        viewModelScope.launch {
//            writableState.value = state.value.copy(
//                sending = true,
//                error = false
//            )
//            if(repository.sendText(glasses, listOf(
//                listOf(
//                    "Test A Test A Test A Test A Test A",
//                    "Test A Test A Test A Test A Test A",
//                    "Test A Test A Test A Test A Test A",
//                    "Test A Test A Test A Test A Test A",
//                    "Test A Test A Test A Test A Test A",
//                ),
//                listOf(
//                    "Test B Test B Test B Test B Test B",
//                    "Test B Test B Test B Test B Test B",
//                    "Test B Test B Test B Test B Test B",
//                    "Test B Test B Test B Test B Test B",
//                    "Test B Test B Test B Test B Test B",
//                ),
//                listOf(
//                    "Test C Test C Test C Test C Test C",
//                    "Test C Test C Test C Test C Test C",
//                    "Test C Test C Test C Test C Test C",
//                    "Test C Test C Test C Test C Test C",
//                    "Test C Test C Test C Test C Test C",
//                ),
//            ))) {
//                writableState.value = state.value.copy(
//                    sending = false,
//                    error = false
//                )
//            } else {
//                writableState.value = state.value.copy(
//                    sending = false,
//                    error = true
//                )
//            }
        }
    }
}