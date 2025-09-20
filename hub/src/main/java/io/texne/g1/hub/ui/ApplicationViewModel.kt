package io.texne.g1.hub.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.texne.g1.basis.client.G1ServiceCommon
import io.texne.g1.basis.client.G1ServiceCommon.ServiceStatus
import io.texne.g1.hub.model.Repository
import io.texne.g1.hub.preferences.AssistantPreferences
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ApplicationViewModel @Inject constructor(
    private val repository: Repository,
    private val assistantPreferences: AssistantPreferences
): ViewModel() {

    data class State(
        val connectedGlasses: G1ServiceCommon.Glasses? = null,
        val error: Boolean = false,
        val scanning: Boolean = false,
        val nearbyGlasses: List<G1ServiceCommon.Glasses>? = null,
        val availableLeftDevices: List<G1ServiceCommon.AvailableDevice> = emptyList(),
        val availableRightDevices: List<G1ServiceCommon.AvailableDevice> = emptyList(),
        val selectedLeftDeviceAddress: String? = null,
        val selectedRightDeviceAddress: String? = null,
        val selectedSection: AppSection = AppSection.GLASSES
    )

    private val selectedSection = MutableStateFlow(AppSection.GLASSES)

    private val activationEvents = MutableSharedFlow<G1ServiceCommon.GestureEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val assistantActivationEvents = activationEvents.asSharedFlow()

    private val activationPreference = assistantPreferences.observeActivationGesture()

    val state = combine(
        repository.getServiceStateFlow(),
        repository.observeScannerSelection(),
        selectedSection
    ) { serviceState, selection, section ->
        State(
            connectedGlasses = serviceState?.glasses?.firstOrNull { it.status == G1ServiceCommon.GlassesStatus.CONNECTED },
            error = serviceState?.status == ServiceStatus.ERROR,
            scanning = serviceState?.status == ServiceStatus.LOOKING,
            nearbyGlasses = if(serviceState == null || serviceState.status == ServiceStatus.READY) null else serviceState.glasses,
            availableLeftDevices = serviceState?.availableLeftDevices ?: emptyList(),
            availableRightDevices = serviceState?.availableRightDevices ?: emptyList(),
            selectedLeftDeviceAddress = selection.leftAddress,
            selectedRightDeviceAddress = selection.rightAddress,
            selectedSection = section
        )
    }.stateIn(viewModelScope, SharingStarted.Lazily, State())

    fun scan() {
        repository.startLooking()
    }

    fun connectSelectedDevices() {
        viewModelScope.launch {
            val selection = repository.currentScannerSelection()
            val left = selection.leftAddress
            val right = selection.rightAddress
            if(left != null && right != null) {
                repository.connectDevices(left, right)
            }
        }
    }

    fun disconnect(id: String) {
        repository.disconnectGlasses(id)
    }

    fun selectLeftDevice(address: String?) {
        repository.selectLeftDevice(address)
    }

    fun selectRightDevice(address: String?) {
        repository.selectRightDevice(address)
    }

    fun selectSection(section: AppSection) {
        selectedSection.value = section
    }

    init {
        viewModelScope.launch {
            repository.gestureEvents().collect { gesture ->
                val preferred = activationPreference.value
                if(gesture.type == preferred && gesture.side == G1ServiceCommon.GestureSide.RIGHT) {
                    activationEvents.emit(gesture)
                    if(selectedSection.value != AppSection.ASSISTANT) {
                        selectedSection.value = AppSection.ASSISTANT
                    }
                }
            }
        }
    }
}