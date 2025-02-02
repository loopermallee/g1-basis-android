package io.texne.g1.basis.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.texne.g1.basis.client.G1ServiceClient.GlassesStatus
import io.texne.g1.basis.example.model.Repository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ApplicationFrameViewModel @Inject constructor(
    private val repository: Repository
): ViewModel() {
    val connectedGlasses = repository.getServiceStateFlow().map { it?.glasses?.filter { it -> it.status == GlassesStatus.CONNECTED } ?: listOf() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, listOf())
}