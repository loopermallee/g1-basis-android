package io.texne.g1.basis.example.ui.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.texne.g1.basis.example.model.Repository
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GlassesScannerViewModel @Inject constructor(
    private val repository: Repository
): ViewModel() {

    val state = repository.getServiceStateFlow()

    fun startLooking() {
        repository.startLooking()
    }

    fun connectGlasses(id: String) {
        viewModelScope.launch {
            repository.connectGlasses(id)
        }
    }

    fun disconnectGlasses(id: String) {
        viewModelScope.launch {
            repository.disconnectGlasses(id)
        }
    }

    override fun onCleared() {
        super.onCleared()
    }
}