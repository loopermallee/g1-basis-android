package io.texne.g1.basis.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.texne.g1.basis.example.model.Repository
import io.texne.g1.basis.service.protocol.G1Glasses
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ApplicationFrameViewModel @Inject constructor(
    private val repository: Repository
): ViewModel() {
    val connectedGlasses = repository.getServiceStateFlow().map { it?.glasses?.filter { it -> it.connectionState == G1Glasses.CONNECTED } ?: listOf() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, listOf())
}