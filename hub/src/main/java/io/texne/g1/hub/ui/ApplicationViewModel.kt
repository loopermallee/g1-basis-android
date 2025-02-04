package io.texne.g1.hub.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.texne.g1.basis.client.G1ServiceCommon
import io.texne.g1.basis.client.G1ServiceCommon.ServiceStatus
import io.texne.g1.hub.model.Repository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ApplicationViewModel @Inject constructor(
    private val repository: Repository
): ViewModel() {

    data class State(
        val connectedGlasses: G1ServiceCommon.Glasses? = null,
        val error: Boolean = false,
        val scanning: Boolean = false,
        val nearbyGlasses: List<G1ServiceCommon.Glasses>? = null
    )

    val state = repository.getServiceStateFlow().map {
        State(
            connectedGlasses = it?.glasses?.filter { it.status == G1ServiceCommon.GlassesStatus.CONNECTED }?.firstOrNull(),
            error = it?.status == ServiceStatus.ERROR,
            scanning = it?.status == ServiceStatus.LOOKING,
            nearbyGlasses = if(it == null || it.status == ServiceStatus.READY) null else it.glasses
        )
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    fun scan() {
        repository.startLooking()
    }

    fun connect(id: String) {
        viewModelScope.launch {
            repository.connectGlasses(id)
        }
    }

    fun disconnect(id: String) {
        repository.disconnectGlasses(id)
    }
}