package io.texne.g1.hub.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.texne.g1.basis.client.G1ServiceCommon
import io.texne.g1.basis.client.G1ServiceCommon.ServiceStatus
import io.texne.g1.hub.ble.G1Connector
import io.texne.g1.hub.model.Repository
import io.texne.g1.hub.model.Repository.GlassesSnapshot
import io.texne.g1.hub.preferences.AssistantPreferences
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withContext
import android.util.Log

@HiltViewModel
class ApplicationViewModel @Inject constructor(
    private val repository: Repository,
    private val assistantPreferences: AssistantPreferences,
    private val connector: G1Connector
) : ViewModel() {

    companion object {
        private const val TAG = "ApplicationVM"
        private const val STATUS_LOOKING = "Looking for glasses…"
        private const val STATUS_TRY_BONDED = "Trying bonded connect…"
        private const val SUCCESS_TITLE = "Paired successfully! You're good to go."
        private const val SUCCESS_SUBTITLE = "Your Even G1 connection ladder finished without needing a manual scan."
        private const val PERMISSION_TITLE = "Nearby devices permission required"
        private const val PERMISSION_SUBTITLE = "Bluetooth permission is required. Please allow 'Nearby devices'."
        private const val FAILURE_TITLE = "Couldn't find or connect to glasses."
        private const val FAILURE_SUBTITLE = "Tip: Unfold glasses, close the Even app (MentraOS), toggle Bluetooth, and retry."
        private const val SUCCESS_SNACKBAR = "Connected to glasses"
        private const val PERMISSION_SNACKBAR = "Bluetooth permission is required."
        private const val FAILURE_SNACKBAR = "Couldn't find or connect to glasses."
        private const val FEEDBACK_AUTO_CLEAR_MILLIS = 4_000L
        private const val RETRY_DELAY_SECONDS = 10
    }

    data class RetryCountdown(
        val secondsRemaining: Int,
        val nextAttemptAtMillis: Long
    )

    data class State(
        val connectedGlasses: GlassesSnapshot? = null,
        val error: Boolean = false,
        val scanning: Boolean = false,
        val serviceStatus: ServiceStatus = ServiceStatus.READY,
        val nearbyGlasses: List<GlassesSnapshot>? = null,
        val selectedSection: AppSection = AppSection.GLASSES,
        val retryCountdowns: Map<String, RetryCountdown> = emptyMap(),
        val telemetryEntries: List<TelemetryEntry> = emptyList(),
        val connectionFeedback: ConnectionFeedback? = null,
        val statusMessage: String? = null,
        val errorMessage: String? = null
    )

    data class TelemetryEntry(
        val id: String,
        val name: String,
        val status: G1ServiceCommon.GlassesStatus,
        val signalStrength: Int?,
        val rssi: Int?,
        val retryCount: Int
    )

    sealed class UiMessage {
        data class AutoConnectTriggered(val glassesName: String) : UiMessage()
        data class AutoConnectFailed(val glassesName: String) : UiMessage()
        data class Snackbar(val text: String) : UiMessage()
    }

    private enum class AttemptType { MANUAL, AUTO }

    private data class AttemptState(
        var lastStatus: G1ServiceCommon.GlassesStatus? = null,
        var hasAttemptStarted: Boolean = false,
        var lastAttemptType: AttemptType? = null,
        var retryCount: Int = 0
    )

    data class ConnectionFeedback(
        val type: Type,
        val title: String,
        val subtitle: String? = null
    ) {
        enum class Type { Success, Failure, Permission }
    }

    private val selectedSection = MutableStateFlow(AppSection.GLASSES)

    private val retryCountdowns = MutableStateFlow<Map<String, RetryCountdown>>(emptyMap())
    private val retryJobs = mutableMapOf<String, Job>()
    private val connectionAttempts = mutableMapOf<String, AttemptState>()
    private val retryCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val statusMessage = MutableStateFlow<String?>(null)
    private val errorMessage = MutableStateFlow<String?>(null)
    private val connectionFeedback = MutableStateFlow<ConnectionFeedback?>(null)

    private var feedbackClearJob: Job? = null

    private val messages = MutableSharedFlow<UiMessage>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val uiMessages = messages.asSharedFlow()
    private var latestServiceSnapshot: Repository.ServiceSnapshot? = null

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
        retryCountdowns,
        retryCounts,
        statusMessage,
        errorMessage,
        connectionFeedback
    ) { serviceState, section, retries, retryStats, statusText, errorText, feedback ->
        State(
            connectedGlasses = serviceState?.glasses?.firstOrNull { it.status == G1ServiceCommon.GlassesStatus.CONNECTED },
            error = serviceState?.status == ServiceStatus.ERROR,
            scanning = serviceState?.status == ServiceStatus.LOOKING,
            serviceStatus = serviceState?.status ?: ServiceStatus.READY,
            nearbyGlasses = if (serviceState == null || serviceState.status == ServiceStatus.READY) null else serviceState.glasses,
            selectedSection = section,
            retryCountdowns = retries,
            telemetryEntries = serviceState?.glasses?.map { glasses ->
                TelemetryEntry(
                    id = glasses.id,
                    name = glasses.name,
                    status = glasses.status,
                    signalStrength = glasses.signalStrength,
                    rssi = glasses.rssi,
                    retryCount = retryStats[glasses.id] ?: 0
                )
            } ?: emptyList(),
            connectionFeedback = feedback,
            statusMessage = statusText,
            errorMessage = errorText
        )
    }.stateIn(viewModelScope, SharingStarted.Lazily, State())

    fun scan() {
        viewModelScope.launch {
            showStatus(STATUS_LOOKING)
            repository.startLooking()
            val result = try {
                withContext(Dispatchers.IO) { connector.connectSmart() }
            } catch (error: Throwable) {
                Log.w(TAG, "connectSmart error", error)
                G1Connector.Result.NotFound
            }
            when (result) {
                is G1Connector.Result.Success -> {
                    showConnectionSuccess()
                    notify(UiMessage.Snackbar(SUCCESS_SNACKBAR))
                }
                is G1Connector.Result.PermissionMissing -> {
                    showPermissionError()
                }
                is G1Connector.Result.NotFound -> {
                    showConnectionFailure()
                }
            }
        }
    }

    fun connect(id: String) {
        clearStatus()
        val attempt = connectionAttempts.getOrPut(id) { AttemptState() }
        attempt.hasAttemptStarted = false
        attempt.lastAttemptType = AttemptType.MANUAL
        attempt.retryCount = 0
        connectionAttempts[id] = attempt
        updateRetryCount(id, attempt.retryCount)
        clearRetryCountdown(id, removeRequest = false)
        viewModelScope.launch {
            repository.connectGlasses(id)
        }
    }

    fun disconnect(id: String) {
        clearStatus()
        clearRetryCountdown(id, removeRequest = true)
        repository.disconnectGlasses(id)
    }

    fun cancelAutoRetry(id: String) {
        clearRetryCountdown(id, removeRequest = true)
    }

    fun retryNow(id: String) {
        clearStatus()
        clearRetryCountdown(id, removeRequest = false)
        connect(id)
    }

    fun tryBondedConnect() {
        viewModelScope.launch {
            showStatus(STATUS_TRY_BONDED)
            val result = try {
                withContext(Dispatchers.IO) { connector.tryBondedConnect() }
            } catch (error: Throwable) {
                Log.w(TAG, "tryBondedConnect error", error)
                G1Connector.Result.NotFound
            }
            when (result) {
                is G1Connector.Result.Success -> {
                    showConnectionSuccess()
                    notify(UiMessage.Snackbar(SUCCESS_SNACKBAR))
                }
                is G1Connector.Result.PermissionMissing -> {
                    showPermissionError()
                }
                is G1Connector.Result.NotFound -> {
                    showConnectionFailure()
                    repository.startLooking()
                }
            }
        }
    }

    fun selectSection(section: AppSection) {
        selectedSection.value = section
    }

    private fun glassesName(id: String): String =
        latestServiceSnapshot?.glasses?.firstOrNull { it.id == id }?.name ?: id

    private fun notify(message: UiMessage) {
        viewModelScope.launch {
            messages.emit(message)
        }
    }

    private fun showStatus(text: String) {
        feedbackClearJob?.cancel()
        connectionFeedback.value = null
        statusMessage.value = text
        errorMessage.value = null
    }

    private fun showConnectionFailure() {
        clearStatus()
        Log.i(TAG, "TIP_SHOWN=CLOSE_EVEN")
        notify(UiMessage.Snackbar(FAILURE_SNACKBAR))
        showFeedback(
            ConnectionFeedback(
                type = ConnectionFeedback.Type.Failure,
                title = FAILURE_TITLE,
                subtitle = FAILURE_SUBTITLE
            )
        )
    }

    private fun showPermissionError() {
        clearStatus()
        notify(UiMessage.Snackbar(PERMISSION_SNACKBAR))
        showFeedback(
            ConnectionFeedback(
                type = ConnectionFeedback.Type.Permission,
                title = PERMISSION_TITLE,
                subtitle = PERMISSION_SUBTITLE
            )
        )
    }

    private fun clearStatus() {
        statusMessage.value = null
        errorMessage.value = null
        feedbackClearJob?.cancel()
        connectionFeedback.value = null
    }

    private fun showConnectionSuccess() {
        clearStatus()
        showFeedback(
            ConnectionFeedback(
                type = ConnectionFeedback.Type.Success,
                title = SUCCESS_TITLE,
                subtitle = SUCCESS_SUBTITLE
            )
        )
    }

    private fun showFeedback(feedback: ConnectionFeedback) {
        feedbackClearJob?.cancel()
        connectionFeedback.value = feedback
        feedbackClearJob = viewModelScope.launch {
            delay(FEEDBACK_AUTO_CLEAR_MILLIS)
            if (connectionFeedback.value == feedback) {
                connectionFeedback.value = null
            }
        }
    }

    private fun updateRetryCount(id: String, count: Int) {
        retryCounts.update { current ->
            current.toMutableMap().apply { this[id] = count }
        }
    }

    private fun removeRetryCount(id: String) {
        retryCounts.update { current ->
            if (current.containsKey(id)) {
                current.toMutableMap().apply { remove(id) }
            } else {
                current
            }
        }
    }

    private fun startAutoReconnect(id: String) {
        val attempt = connectionAttempts.getOrPut(id) { AttemptState() }
        attempt.hasAttemptStarted = false
        attempt.lastAttemptType = AttemptType.AUTO
        attempt.retryCount += 1
        connectionAttempts[id] = attempt
        updateRetryCount(id, attempt.retryCount)
        val name = glassesName(id)
        notify(UiMessage.AutoConnectTriggered(name))
        viewModelScope.launch {
            repository.connectGlasses(id)
        }
    }

    init {
        viewModelScope.launch {
            repository.getServiceStateFlow().collect { serviceState ->
                latestServiceSnapshot = serviceState
                val glasses = serviceState?.glasses.orEmpty()
                val availableIds = glasses.map { it.id }.toSet()
                val inactiveIds = connectionAttempts.keys.filterNot { availableIds.contains(it) }
                inactiveIds.forEach { id ->
                    clearRetryCountdown(id, removeRequest = true)
                    removeRetryCount(id)
                }
                glasses.forEach { snapshot ->
                    val id = snapshot.id
                    val attempt = connectionAttempts[id]
                    if (attempt == null) {
                        if (!retryCounts.value.containsKey(id)) {
                            updateRetryCount(id, 0)
                        }
                        return@forEach
                    }
                    if (!retryCounts.value.containsKey(id)) {
                        updateRetryCount(id, attempt.retryCount)
                    }
                    val previousStatus = attempt.lastStatus
                    attempt.lastStatus = snapshot.status
                    when (snapshot.status) {
                        G1ServiceCommon.GlassesStatus.CONNECTED -> {
                            attempt.lastAttemptType = null
                            clearRetryCountdown(id, removeRequest = true)
                        }
                        G1ServiceCommon.GlassesStatus.CONNECTING,
                        G1ServiceCommon.GlassesStatus.DISCONNECTING -> {
                            attempt.hasAttemptStarted = true
                            clearRetryCountdown(id, removeRequest = false)
                        }
                        G1ServiceCommon.GlassesStatus.ERROR -> {
                            attempt.hasAttemptStarted = true
                            if (attempt.lastAttemptType == AttemptType.AUTO) {
                                notify(UiMessage.AutoConnectFailed(snapshot.name))
                                attempt.lastAttemptType = null
                            }
                            scheduleRetry(id)
                        }
                        G1ServiceCommon.GlassesStatus.DISCONNECTED -> {
                            if (
                                attempt.hasAttemptStarted ||
                                previousStatus == G1ServiceCommon.GlassesStatus.CONNECTING ||
                                previousStatus == G1ServiceCommon.GlassesStatus.DISCONNECTING ||
                                previousStatus == G1ServiceCommon.GlassesStatus.CONNECTED
                            ) {
                                if (attempt.lastAttemptType == AttemptType.AUTO) {
                                    notify(UiMessage.AutoConnectFailed(snapshot.name))
                                    attempt.lastAttemptType = null
                                }
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
                if (gesture.type == preferred && gesture.side == G1ServiceCommon.GestureSide.RIGHT) {
                    activationEvents.emit(gesture)
                    if (selectedSection.value != AppSection.ASSISTANT) {
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
                    retryCountdowns.update { current ->
                        current.toMutableMap().apply { remove(id) }
                    }
                    return@launch
                }
                remaining -= 1
                retryCountdowns.update { current ->
                    current.toMutableMap().apply {
                        this[id] = RetryCountdown(remaining, nextAttemptAt)
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
                current.toMutableMap().apply { remove(id) }
            }
            retryJobs.remove(id)
            startAutoReconnect(id)
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
}
