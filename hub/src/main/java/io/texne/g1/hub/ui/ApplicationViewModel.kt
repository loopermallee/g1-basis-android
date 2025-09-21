package io.texne.g1.hub.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.texne.g1.basis.client.G1ServiceCommon
import io.texne.g1.basis.client.G1ServiceCommon.ServiceStatus
import io.texne.g1.hub.ble.G1Connector
import io.texne.g1.hub.model.Repository
import io.texne.g1.hub.model.Repository.GlassesSnapshot
import io.texne.g1.hub.preferences.AssistantPreferences
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
import javax.inject.Inject

@HiltViewModel
class ApplicationViewModel @Inject constructor(
    private val repository: Repository,
    private val assistantPreferences: AssistantPreferences,
    private val connector: G1Connector,
    @ApplicationContext private val appContext: Context
): ViewModel() {

    companion object {
        private const val TAG = "ApplicationVM"
        private const val STATUS_LOOKING = "Looking for glasses…"
        private const val STATUS_CONNECTED = "Connected"
        private const val STATUS_TRY_BONDED = "Trying bonded connect…"
        private const val PERMISSION_MESSAGE = "Bluetooth permission is required. Please allow 'Nearby devices'."
        private const val FAILURE_MESSAGE = "Couldn’t find/connect to glasses.\nTips: Unfold glasses, close the Even app (MentraOS), toggle Bluetooth, and retry."
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

    private val selectedSection = MutableStateFlow(AppSection.GLASSES)

    private val retryCountdowns = MutableStateFlow<Map<String, RetryCountdown>>(emptyMap())
    private val retryJobs = mutableMapOf<String, Job>()
    private val connectionAttempts = mutableMapOf<String, AttemptState>()
    private val retryCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val statusMessage = MutableStateFlow<String?>(null)
    private val errorMessage = MutableStateFlow<String?>(null)

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
        errorMessage
    ) { serviceState, section, retries, retryStats, statusText, errorText ->
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
            statusMessage = statusText,
            errorMessage = errorText
        )
    }.stateIn(viewModelScope, SharingStarted.Lazily, State())

    fun scan() {
        viewModelScope.launch {
            if (!ensureScanPermissions()) {
                return@launch
            }
            showStatus(STATUS_LOOKING)
            repository.startLooking()
            val success = try {
                connector.connectSmart()
            } catch (error: Throwable) {
                Log.w(TAG, "connectSmart error", error)
                false
            }
            if (success) {
                showStatus(STATUS_CONNECTED)
                notify(UiMessage.Snackbar("Connected to glasses"))
            } else {
                showConnectionFailure()
            }
        }
    }

    fun connect(id: String) {
        val attempt = connectionAttempts.getOrPut(id) { AttemptState() }
        attempt.hasAttemptStarted = false
        attempt.lastAttemptType = AttemptType.MANUAL
        attempt.retryCount = 0
        connectionAttempts[id] = attempt
        updateRetryCount(id, attempt.retryCount)
        clearRetryCountdown(id, removeRequest = false)
        clearStatus()
        viewModelScope.launch {
            repository.connectGlasses(id)
        }
    }

    fun disconnect(id: String) {
        clearRetryCountdown(id, removeRequest = true)
        clearStatus()
        repository.disconnectGlasses(id)
    }

    fun cancelAutoRetry(id: String) {
        clearRetryCountdown(id, removeRequest = true)
    }

    fun retryNow(id: String) {
        clearRetryCountdown(id, removeRequest = false)
        connect(id)
    }

    fun tryBondedConnect() {
        viewModelScope.launch {
            if (!ensureConnectPermission()) {
                return@launch
            }
            showStatus(STATUS_TRY_BONDED)
            val success = try {
                withContext(Dispatchers.IO) { connector.tryBondedConnect() }
            } catch (error: Throwable) {
                Log.w(TAG, "tryBondedConnect error", error)
                false
            }
            if (success) {
                showStatus(STATUS_CONNECTED)
                notify(UiMessage.Snackbar("Bonded connect succeeded"))
            } else {
                showConnectionFailure()
                repository.startLooking()
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
        statusMessage.value = text
        errorMessage.value = null
    }

    private fun showConnectionFailure() {
        statusMessage.value = null
        errorMessage.value = FAILURE_MESSAGE
        Log.i(TAG, "TIP_SHOWN=CLOSE_EVEN")
        notify(UiMessage.Snackbar(FAILURE_MESSAGE))
    }

    private fun showPermissionError() {
        statusMessage.value = null
        errorMessage.value = PERMISSION_MESSAGE
        notify(UiMessage.Snackbar(PERMISSION_MESSAGE))
    }

    private fun clearStatus() {
        statusMessage.value = null
        errorMessage.value = null
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun ensureScanPermissions(): Boolean {
        var missing = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                Log.w(TAG, "PERM_MISSING=CONNECT")
                missing = true
            }
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                Log.w(TAG, "PERM_MISSING=SCAN")
                missing = true
            }
        } else {
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                Log.w(TAG, "PERM_MISSING=SCAN")
                missing = true
            }
        }
        if (missing) {
            showPermissionError()
        }
        return !missing
    }

    private fun ensureConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            Log.w(TAG, "PERM_MISSING=CONNECT")
            showPermissionError()
            return false
        }
        return true
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

    companion object {
        private const val RETRY_DELAY_SECONDS = 10
    }
}