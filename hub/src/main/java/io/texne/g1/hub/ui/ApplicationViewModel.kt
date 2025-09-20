package io.texne.g1.hub.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.texne.g1.basis.client.G1ServiceCommon
import io.texne.g1.basis.client.G1ServiceCommon.ServiceStatus
import io.texne.g1.hub.model.Repository
import io.texne.g1.hub.model.Repository.GlassesSnapshot
import io.texne.g1.hub.preferences.AssistantPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ApplicationViewModel @Inject constructor(
    private val repository: Repository,
    private val assistantPreferences: AssistantPreferences
): ViewModel() {

    data class RetryCountdown(
        val secondsRemaining: Int,
        val nextAttemptAtMillis: Long
    )

    data class State(
        val connectedGlasses: GlassesSnapshot? = null,
        val error: Boolean = false,
        val scanning: Boolean = false,
        val nearbyGlasses: List<GlassesSnapshot>? = null,
        val selectedSection: AppSection = AppSection.GLASSES,
        val retryCountdowns: Map<String, RetryCountdown> = emptyMap()
    )

    private data class AttemptState(
        var lastStatus: G1ServiceCommon.GlassesStatus? = null,
        var hasAttemptStarted: Boolean = false
    )

    private val selectedSection = MutableStateFlow(AppSection.GLASSES)

    private val retryCountdowns = MutableStateFlow<Map<String, RetryCountdown>>(emptyMap())
    private val retryJobs = mutableMapOf<String, Job>()
    private val connectionAttempts = mutableMapOf<String, AttemptState>()

    private val activationEvents = MutableSharedFlow<G1ServiceCommon.GestureEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val assistantActivationEvents = activationEvents.asSharedFlow()

    private val activationPreference = assistantPreferences.observeActivationGesture()

    val state = combine(
        repository.getServiceStateFlow(),
        selectedSection,
        retryCountdowns
    ) { serviceState, section, retries ->
        State(
            connectedGlasses = serviceState?.glasses?.firstOrNull { it.status == G1ServiceCommon.GlassesStatus.CONNECTED },
            error = serviceState?.status == ServiceStatus.ERROR,
            scanning = serviceState?.status == ServiceStatus.LOOKING,
            nearbyGlasses = if (serviceState == null || serviceState.status == ServiceStatus.READY) null else serviceState.glasses,
            selectedSection = section,
            retryCountdowns = retries
        )
    }.stateIn(viewModelScope, SharingStarted.Lazily, State())

    fun scan() {
        repository.startLooking()
    }

    fun connect(id: String) {
        val attempt = connectionAttempts[id] ?: AttemptState()
        attempt.hasAttemptStarted = false
        connectionAttempts[id] = attempt
        clearRetryCountdown(id, removeRequest = false)
        viewModelScope.launch {
            repository.connectGlasses(id)
        }
    }

    fun disconnect(id: String) {
        clearRetryCountdown(id, removeRequest = true)
        repository.disconnectGlasses(id)
    }

    fun cancelAutoRetry(id: String) {
        clearRetryCountdown(id, removeRequest = true)
    }

    fun retryNow(id: String) {
        clearRetryCountdown(id, removeRequest = false)
        connect(id)
    }

    fun selectSection(section: AppSection) {
        selectedSection.value = section
    }

    init {
        viewModelScope.launch {
            repository.getServiceStateFlow().collect { serviceState ->
                val glasses = serviceState?.glasses.orEmpty()
                val availableIds = glasses.map { it.id }.toSet()
                val inactiveIds = connectionAttempts.keys.filterNot { availableIds.contains(it) }
                inactiveIds.forEach { id ->
                    clearRetryCountdown(id, removeRequest = true)
                }
                glasses.forEach { snapshot ->
                    val id = snapshot.id
                    val attempt = connectionAttempts[id] ?: return@forEach
                    val previousStatus = attempt.lastStatus
                    attempt.lastStatus = snapshot.status
                    when (snapshot.status) {
                        G1ServiceCommon.GlassesStatus.CONNECTED -> clearRetryCountdown(id, removeRequest = true)
                        G1ServiceCommon.GlassesStatus.CONNECTING,
                        G1ServiceCommon.GlassesStatus.DISCONNECTING -> {
                            attempt.hasAttemptStarted = true
                            clearRetryCountdown(id, removeRequest = false)
                        }
                        G1ServiceCommon.GlassesStatus.ERROR -> {
                            attempt.hasAttemptStarted = true
                            scheduleRetry(id)
                        }
                        G1ServiceCommon.GlassesStatus.DISCONNECTED -> {
                            if (
                                attempt.hasAttemptStarted ||
                                previousStatus == G1ServiceCommon.GlassesStatus.CONNECTING ||
                                previousStatus == G1ServiceCommon.GlassesStatus.DISCONNECTING ||
                                previousStatus == G1ServiceCommon.GlassesStatus.CONNECTED
                            ) {
                                scheduleRetry(id)
                            }
                        }
                        else -> {}
                    }
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

    private fun scheduleRetry(id: String) {
        if (!connectionAttempts.containsKey(id)) {
            return
        }
        if (retryJobs.containsKey(id) || retryCountdowns.value.containsKey(id)) {
            return
        }
        val nextAttemptAt = System.currentTimeMillis() + RETRY_DELAY_SECONDS * 1_000L
        retryCountdowns.update { current ->
            current.toMutableMap().apply {
                this[id] = RetryCountdown(RETRY_DELAY_SECONDS, nextAttemptAt)
            }
        }
        val job = viewModelScope.launch {
            var remaining = RETRY_DELAY_SECONDS
            while (isActive && remaining > 0 && connectionAttempts.containsKey(id)) {
                delay(1_000L)
                if (!connectionAttempts.containsKey(id)) {
                    break
                }
                remaining -= 1
                retryCountdowns.update { current ->
                    if (!connectionAttempts.containsKey(id)) {
                        current.toMutableMap().apply { remove(id) }
                    } else {
                        current.toMutableMap().apply {
                            this[id] = RetryCountdown(remaining, nextAttemptAt)
                        }
                    }
                }
            }
            if (!connectionAttempts.containsKey(id)) {
                retryCountdowns.update { current ->
                    current.toMutableMap().apply { remove(id) }
                }
                return@launch
            }
            retryCountdowns.update { current ->
                current.toMutableMap().apply {
                    this[id] = RetryCountdown(0, nextAttemptAt)
                }
            }
        }
        job.invokeOnCompletion {
            retryJobs.remove(id)
        }
        retryJobs[id] = job
    }

    private fun clearRetryCountdown(id: String, removeRequest: Boolean) {
        retryJobs.remove(id)?.cancel()
        retryCountdowns.update { current ->
            if (current.containsKey(id)) {
                current.toMutableMap().apply { remove(id) }
            } else {
                current
            }
        }
        if (removeRequest) {
            connectionAttempts.remove(id)
        }
    }

    companion object {
        private const val RETRY_DELAY_SECONDS = 10
    }
}