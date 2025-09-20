package io.texne.g1.hub.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.texne.g1.basis.client.G1ServiceCommon
import io.texne.g1.basis.client.G1ServiceCommon.ServiceStatus
import io.texne.g1.hub.model.Repository
import io.texne.g1.hub.preferences.AssistantPreferences
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
        val selectedSection: AppSection = AppSection.GLASSES,
        val retry: RetryCountdown? = null
    )

    data class RetryCountdown(
        val glassesId: String,
        val secondsRemaining: Int,
        val nextAttemptAtMillis: Long,
        val readyToRetry: Boolean
    )

    private val selectedSection = MutableStateFlow(AppSection.GLASSES)

    private val activationEvents = MutableSharedFlow<G1ServiceCommon.GestureEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val assistantActivationEvents = activationEvents.asSharedFlow()

    private val activationPreference = assistantPreferences.observeActivationGesture()

    private val retryState = MutableStateFlow<RetryCountdown?>(null)
    private val autoRetryTargetId = MutableStateFlow<String?>(null)
    private var retryJob: Job? = null

    val state = combine(
        repository.getServiceStateFlow(),
        selectedSection,
        retryState
    ) { serviceState, section, retry ->
        State(
            connectedGlasses = serviceState?.glasses?.firstOrNull { it.status == G1ServiceCommon.GlassesStatus.CONNECTED },
            error = serviceState?.status == ServiceStatus.ERROR,
            scanning = serviceState?.status == ServiceStatus.LOOKING,
            nearbyGlasses = if(serviceState == null || serviceState.status == ServiceStatus.READY) null else serviceState.glasses,
            selectedSection = section,
            retry = retry
        )
    }.stateIn(viewModelScope, SharingStarted.Lazily, State())

    fun scan() {
        repository.startLooking()
    }

    fun connect(id: String) {
        autoRetryTargetId.value = id
        cancelRetryCountdown()
        viewModelScope.launch {
            val success = repository.connectGlasses(id)
            if (!success) {
                scheduleRetry(id)
            }
        }
    }

    fun disconnect(id: String) {
        if (autoRetryTargetId.value == id) {
            cancelRetryCountdown(clearTarget = true)
        }
        repository.disconnectGlasses(id)
    }

    fun selectSection(section: AppSection) {
        selectedSection.value = section
    }

    fun cancelRetry(glassesId: String) {
        if (retryState.value?.glassesId == glassesId) {
            cancelRetryCountdown(clearTarget = true)
        }
    }

    fun retryNow(glassesId: String) {
        connect(glassesId)
    }

    init {
        viewModelScope.launch {
            repository.getServiceStateFlow().collect { serviceState ->
                val targetId = autoRetryTargetId.value ?: return@collect
                val targetGlasses = serviceState?.glasses?.firstOrNull { it.id == targetId }
                when (targetGlasses?.status) {
                    G1ServiceCommon.GlassesStatus.CONNECTED -> cancelRetryCountdown()
                    G1ServiceCommon.GlassesStatus.ERROR,
                    G1ServiceCommon.GlassesStatus.DISCONNECTED -> scheduleRetry(targetId)
                    else -> Unit
                }
            }
        }
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

    private fun scheduleRetry(glassesId: String) {
        if (autoRetryTargetId.value != glassesId) {
            return
        }
        val current = retryState.value
        if (retryJob?.isActive == true || (current?.glassesId == glassesId && current.readyToRetry)) {
            return
        }
        retryJob?.cancel()
        val nextAttemptAt = System.currentTimeMillis() + RETRY_DELAY_SECONDS * MILLIS_IN_SECOND
        retryState.value = RetryCountdown(
            glassesId = glassesId,
            secondsRemaining = RETRY_DELAY_SECONDS,
            nextAttemptAtMillis = nextAttemptAt,
            readyToRetry = false
        )
        retryJob = viewModelScope.launch {
            var remaining = RETRY_DELAY_SECONDS
            while (remaining > 0 && isActive) {
                delay(MILLIS_IN_SECOND)
                remaining -= 1
                retryState.value = RetryCountdown(
                    glassesId = glassesId,
                    secondsRemaining = remaining,
                    nextAttemptAtMillis = nextAttemptAt,
                    readyToRetry = false
                )
            }
            if (!isActive) {
                return@launch
            }
            retryJob = null
            retryState.value = RetryCountdown(
                glassesId = glassesId,
                secondsRemaining = 0,
                nextAttemptAtMillis = nextAttemptAt,
                readyToRetry = true
            )
        }
    }

    private fun cancelRetryCountdown(clearTarget: Boolean = false) {
        retryJob?.cancel()
        retryJob = null
        retryState.value = null
        if (clearTarget) {
            autoRetryTargetId.value = null
        }
    }

    private companion object {
        private const val RETRY_DELAY_SECONDS = 5
        private const val MILLIS_IN_SECOND = 1_000L
    }
}