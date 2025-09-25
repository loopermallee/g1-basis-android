package io.texne.g1.hub.ui

import android.util.Log
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

@HiltViewModel
class ApplicationViewModel @Inject constructor(
    private val repository: Repository,
    private val assistantPreferences: AssistantPreferences,
    private val connector: G1Connector
) : ViewModel() {

    companion object {
        private const val TAG = "ApplicationVM"
        private const val STATUS_LOOKING = "Looking for glasses…"
        private const val STATUS_CONNECTED = "Connected"
        private const val STATUS_TRY_BONDED = "Trying bonded connect…"
        private const val PERMISSION_MESSAGE = "Bluetooth permission is required. Please allow 'Nearby devices'."
        private const val FAILURE_MESSAGE = "Couldn’t find/connect to glasses.\nTips: Unfold glasses, close the Even app (MentraOS), toggle Bluetooth, and retry."
        private const val RETRY_DELAY_SECONDS = 10
        private const val MAX_TELEMETRY_LOG_ENTRIES = 200
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
        val glasses: List<GlassesSnapshot>? = null,
        val retryCountdowns: Map<String, RetryCountdown> = emptyMap(),
        val retryCounts: Map<String, Int> = emptyMap(),
        val telemetryEntries: List<TelemetryEntry> = emptyList(),
        val telemetryLogs: List<TelemetryLogEntry> = emptyList(),
        val errorMessage: String? = null
    )

    data class TelemetryEntry(
        val id: String,
        val name: String,
        val status: G1ServiceCommon.GlassesStatus,
        val leftStatus: G1ServiceCommon.GlassesStatus,
        val rightStatus: G1ServiceCommon.GlassesStatus,
        val batteryPercentage: Int,
        val leftBatteryPercentage: Int,
        val rightBatteryPercentage: Int,
        val signalStrength: Int?,
        val rssi: Int?,
        val retryCount: Int,
        val lastUpdatedAt: Long
    )

    data class TelemetryLogEntry(
        val id: Long,
        val timestampMillis: Long,
        val message: String,
        val type: TelemetryLogType
    )

    enum class TelemetryLogType { INFO, SUCCESS, ERROR }

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

    val glasses: String = ""
    val status: String = "unknown"

    private val retryCountdowns = MutableStateFlow<Map<String, RetryCountdown>>(emptyMap())
    private val retryJobs = mutableMapOf<String, Job>()
    private val connectionAttempts = mutableMapOf<String, AttemptState>()
    private val retryCounts = MutableStateFlow<Map<String, Int>>(emptyMap())

    private val messages = MutableSharedFlow<UiMessage>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val uiMessages = messages.asSharedFlow()
    private var latestServiceSnapshot: Repository.ServiceSnapshot? = null
    private val telemetryLogs = MutableStateFlow<List<TelemetryLogEntry>>(emptyList())
    private var telemetryLogCounter = 0L
    private val telemetryTimestamps = mutableMapOf<String, Long>()

    private val activationEvents = MutableSharedFlow<G1ServiceCommon.GestureEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val assistantActivationEvents = activationEvents.asSharedFlow()

    private val activationPreference = assistantPreferences.observeActivationGesture()

    val state = combine(
        repository.getServiceStateFlow(),
        retryCountdowns,
        retryCounts,
        telemetryLogs,
        ::updateState
    ).stateIn(viewModelScope, SharingStarted.Lazily, State())

    private suspend fun updateState(
        serviceSnapshot: Repository.ServiceSnapshot?,
        countdowns: Map<String, RetryCountdown>,
        counts: Map<String, Int>,
        logs: List<TelemetryLogEntry>
    ): State {
        val glasses = serviceSnapshot?.glasses.orEmpty()
        val serviceStatus = serviceSnapshot?.status ?: ServiceStatus.READY
        val connected = glasses.firstOrNull { snapshot ->
            snapshot.status == G1ServiceCommon.GlassesStatus.CONNECTED
        }

        val currentIds = glasses.map { it.id }.toSet()
        telemetryTimestamps.keys.retainAll(currentIds)

        val telemetryEntries = glasses.map { snapshot ->
            TelemetryEntry(
                id = snapshot.id,
                name = snapshot.name,
                status = snapshot.status,
                leftStatus = snapshot.left.status,
                rightStatus = snapshot.right.status,
                batteryPercentage = snapshot.batteryPercentage,
                leftBatteryPercentage = snapshot.left.batteryPercentage,
                rightBatteryPercentage = snapshot.right.batteryPercentage,
                signalStrength = snapshot.signalStrength,
                rssi = snapshot.rssi,
                retryCount = counts[snapshot.id] ?: 0,
                lastUpdatedAt = telemetryTimestamps[snapshot.id] ?: System.currentTimeMillis()
            )
        }.sortedBy { entry -> entry.name.ifBlank { entry.id } }

        val errorMessage = when (serviceStatus) {
            ServiceStatus.PERMISSION_REQUIRED -> PERMISSION_MESSAGE
            ServiceStatus.ERROR -> FAILURE_MESSAGE
            else -> null
        }

        return State(
            connectedGlasses = connected,
            error = serviceStatus == ServiceStatus.ERROR,
            scanning = serviceStatus == ServiceStatus.LOOKING,
            serviceStatus = serviceStatus,
            glasses = glasses,
            retryCountdowns = countdowns,
            retryCounts = counts,
            telemetryEntries = telemetryEntries,
            telemetryLogs = logs,
            errorMessage = errorMessage
        )
    }

    fun scan() {
        viewModelScope.launch {
            showStatus(STATUS_LOOKING)
            logTelemetry(TelemetryLogType.INFO, "Starting smart connect scan")
            repository.startLooking()
            val result = try {
                withContext(Dispatchers.IO) { connector.connectSmart() }
            } catch (error: Throwable) {
                Log.w(TAG, "connectSmart error", error)
                logTelemetry(
                    TelemetryLogType.ERROR,
                    "Smart connect encountered ${error::class.java.simpleName}: ${error.message ?: "unknown error"}"
                )
                G1Connector.Result.NotFound
            }
            when (result) {
                is G1Connector.Result.Success -> {
                    showStatus(STATUS_CONNECTED)
                    notify(UiMessage.Snackbar("Connected to glasses"))
                    logTelemetry(TelemetryLogType.SUCCESS, "Smart connect succeeded")
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
        logTelemetry(
            TelemetryLogType.INFO,
            "Manual connect requested for ${glassesName(id)} ($id)"
        )
        viewModelScope.launch {
            repository.connectGlasses(id)
        }
    }

    fun disconnect(id: String) {
        clearStatus()
        clearRetryCountdown(id, removeRequest = true)
        logTelemetry(
            TelemetryLogType.INFO,
            "Disconnect requested for ${glassesName(id)} ($id)"
        )
        repository.disconnectGlasses(id)
    }

    fun onPermissionDenied() {
        showPermissionError()
    }

    fun cancelAutoRetry(id: String) {
        logTelemetry(
            TelemetryLogType.INFO,
            "Cancelled auto retry for ${glassesName(id)}"
        )
        clearRetryCountdown(id, removeRequest = true)
    }

    fun retryNow(id: String) {
        clearStatus()
        clearRetryCountdown(id, removeRequest = false)
        logTelemetry(
            TelemetryLogType.INFO,
            "Manual retry triggered for ${glassesName(id)}"
        )
        connect(id)
    }

    fun tryBondedConnect() {
        viewModelScope.launch {
            showStatus(STATUS_TRY_BONDED)
            logTelemetry(TelemetryLogType.INFO, "Attempting bonded connect")
            val result = try {
                withContext(Dispatchers.IO) { connector.tryBondedConnect() }
            } catch (error: Throwable) {
                Log.w(TAG, "tryBondedConnect error", error)
                logTelemetry(
                    TelemetryLogType.ERROR,
                    "Bonded connect encountered ${error::class.java.simpleName}: ${error.message ?: "unknown error"}"
                )
                G1Connector.Result.NotFound
            }
            when (result) {
                is G1Connector.Result.Success -> {
                    showStatus(STATUS_CONNECTED)
                    notify(UiMessage.Snackbar("Bonded connect succeeded"))
                    logTelemetry(TelemetryLogType.SUCCESS, "Bonded connect succeeded")
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

    private fun glassesName(id: String): String {
        return latestServiceSnapshot?.glasses
            ?.firstOrNull { it.id == id }
            ?.name
            ?.takeIf { it.isNotBlank() }
            ?: id
    }

    private fun notify(message: UiMessage) {
        viewModelScope.launch {
            messages.emit(message)
        }
    }

    private fun logTelemetry(type: TelemetryLogType, message: String) {
        telemetryLogs.update { current ->
            val entry = TelemetryLogEntry(
                id = ++telemetryLogCounter,
                timestampMillis = System.currentTimeMillis(),
                message = message,
                type = type
            )
            val updated = current + entry
            if (updated.size > MAX_TELEMETRY_LOG_ENTRIES) {
                updated.takeLast(MAX_TELEMETRY_LOG_ENTRIES)
            } else {
                updated
            }
        }
    }

    private fun handleTelemetrySnapshotChange(
        previous: Repository.ServiceSnapshot?,
        current: Repository.ServiceSnapshot?
    ) {
        if (previous?.status != current?.status) {
            current?.status?.let { status ->
                val (type, text) = serviceStatusLog(status)
                logTelemetry(type, text)
            } ?: run {
                logTelemetry(TelemetryLogType.INFO, "Service telemetry unavailable")
            }
        }

        if (current == null) {
            telemetryTimestamps.clear()
            return
        }

        val previousGlasses = previous?.glasses.orEmpty().associateBy { it.id }
        val now = System.currentTimeMillis()
        val currentIds = mutableSetOf<String>()

        current.glasses.forEach { snapshot ->
            val previousSnapshot = previousGlasses[snapshot.id]
            val displayName = snapshot.name.ifBlank { snapshot.id }
            if (previousSnapshot == null) {
                telemetryTimestamps[snapshot.id] = now
                logTelemetry(
                    TelemetryLogType.INFO,
                    "Receiving telemetry from $displayName (${snapshot.id})"
                )
                val (statusType, statusMessage) = glassesStatusLog(displayName, snapshot.status)
                logTelemetry(statusType, statusMessage)
                val (leftType, leftMessage) = lensStatusLog(
                    side = "Left",
                    name = displayName,
                    status = snapshot.left.status
                )
                logTelemetry(leftType, leftMessage)
                val (rightType, rightMessage) = lensStatusLog(
                    side = "Right",
                    name = displayName,
                    status = snapshot.right.status
                )
                logTelemetry(rightType, rightMessage)
                val (signalType, signalMessage) = signalLog(displayName, snapshot.signalStrength)
                logTelemetry(signalType, signalMessage)
                val (rssiType, rssiMessage) = rssiLog(displayName, snapshot.rssi)
                logTelemetry(rssiType, rssiMessage)
                val (batteryType, batteryMessage) = batteryLog(
                    label = "$displayName overall",
                    percentage = snapshot.batteryPercentage
                )
                logTelemetry(batteryType, batteryMessage)
                val (leftBatteryType, leftBatteryMessage) = batteryLog(
                    label = "$displayName left",
                    percentage = snapshot.left.batteryPercentage
                )
                logTelemetry(leftBatteryType, leftBatteryMessage)
                val (rightBatteryType, rightBatteryMessage) = batteryLog(
                    label = "$displayName right",
                    percentage = snapshot.right.batteryPercentage
                )
                logTelemetry(rightBatteryType, rightBatteryMessage)
            } else {
                if (previousSnapshot != snapshot || !telemetryTimestamps.containsKey(snapshot.id)) {
                    telemetryTimestamps[snapshot.id] = now
                }

                if (previousSnapshot.status != snapshot.status) {
                    val (type, message) = glassesStatusLog(displayName, snapshot.status)
                    logTelemetry(type, message)
                }

                if (previousSnapshot.left.status != snapshot.left.status) {
                    val (type, message) = lensStatusLog(
                        side = "Left",
                        name = displayName,
                        status = snapshot.left.status
                    )
                    logTelemetry(type, message)
                }

                if (previousSnapshot.right.status != snapshot.right.status) {
                    val (type, message) = lensStatusLog(
                        side = "Right",
                        name = displayName,
                        status = snapshot.right.status
                    )
                    logTelemetry(type, message)
                }

                if (previousSnapshot.signalStrength != snapshot.signalStrength) {
                    val (type, message) = signalLog(displayName, snapshot.signalStrength)
                    logTelemetry(type, message)
                }

                if (previousSnapshot.rssi != snapshot.rssi) {
                    val (type, message) = rssiLog(displayName, snapshot.rssi)
                    logTelemetry(type, message)
                }

                if (previousSnapshot.batteryPercentage != snapshot.batteryPercentage) {
                    val (type, message) = batteryLog(
                        label = "$displayName overall",
                        percentage = snapshot.batteryPercentage
                    )
                    logTelemetry(type, message)
                }

                if (previousSnapshot.left.batteryPercentage != snapshot.left.batteryPercentage) {
                    val (type, message) = batteryLog(
                        label = "$displayName left",
                        percentage = snapshot.left.batteryPercentage
                    )
                    logTelemetry(type, message)
                }

                if (previousSnapshot.right.batteryPercentage != snapshot.right.batteryPercentage) {
                    val (type, message) = batteryLog(
                        label = "$displayName right",
                        percentage = snapshot.right.batteryPercentage
                    )
                    logTelemetry(type, message)
                }
            }

            currentIds.add(snapshot.id)
        }

        val removedIds = previousGlasses.keys - currentIds
        removedIds.forEach { id ->
            val name = previousGlasses[id]?.name?.takeIf { it.isNotBlank() } ?: id
            logTelemetry(TelemetryLogType.INFO, "Stopped receiving telemetry from $name")
            telemetryTimestamps.remove(id)
        }
    }

    private fun serviceStatusLog(status: ServiceStatus): Pair<TelemetryLogType, String> = when (status) {
        ServiceStatus.READY -> TelemetryLogType.INFO to "Service ready"
        ServiceStatus.LOOKING -> TelemetryLogType.INFO to "Service is scanning for glasses"
        ServiceStatus.LOOKED -> TelemetryLogType.INFO to "Scan cycle completed"
        ServiceStatus.PERMISSION_REQUIRED -> TelemetryLogType.ERROR to "Bluetooth permission required to continue"
        ServiceStatus.ERROR -> TelemetryLogType.ERROR to "Service reported an error state"
    }

    private fun glassesStatusLog(name: String, status: G1ServiceCommon.GlassesStatus): Pair<TelemetryLogType, String> {
        val type = when (status) {
            G1ServiceCommon.GlassesStatus.CONNECTED -> TelemetryLogType.SUCCESS
            G1ServiceCommon.GlassesStatus.ERROR -> TelemetryLogType.ERROR
            else -> TelemetryLogType.INFO
        }
        return type to "$name status: ${glassesStatusDescription(status)}"
    }

    private fun lensStatusLog(
        side: String,
        name: String,
        status: G1ServiceCommon.GlassesStatus
    ): Pair<TelemetryLogType, String> {
        val type = when (status) {
            G1ServiceCommon.GlassesStatus.CONNECTED -> TelemetryLogType.SUCCESS
            G1ServiceCommon.GlassesStatus.ERROR -> TelemetryLogType.ERROR
            else -> TelemetryLogType.INFO
        }
        return type to "$side eye status for $name: ${glassesStatusDescription(status)}"
    }

    private fun signalLog(name: String, strength: Int?): Pair<TelemetryLogType, String> {
        val type = when (strength) {
            null -> TelemetryLogType.INFO
            4, 3 -> TelemetryLogType.SUCCESS
            2 -> TelemetryLogType.INFO
            else -> TelemetryLogType.ERROR
        }
        return type to "Signal strength for $name is ${signalDescription(strength)}"
    }

    private fun rssiLog(name: String, rssi: Int?): Pair<TelemetryLogType, String> {
        val type = when {
            rssi == null -> TelemetryLogType.INFO
            rssi >= -60 -> TelemetryLogType.SUCCESS
            rssi >= -80 -> TelemetryLogType.INFO
            else -> TelemetryLogType.ERROR
        }
        return type to "RSSI for $name is ${rssiDescription(rssi)}"
    }

    private fun batteryLog(label: String, percentage: Int): Pair<TelemetryLogType, String> {
        val type = when {
            percentage >= 50 -> TelemetryLogType.SUCCESS
            percentage >= 20 -> TelemetryLogType.INFO
            else -> TelemetryLogType.ERROR
        }
        return type to "Battery ($label) at ${percentage.coerceIn(0, 100)}%"
    }

    private fun glassesStatusDescription(status: G1ServiceCommon.GlassesStatus): String = when (status) {
        G1ServiceCommon.GlassesStatus.UNINITIALIZED -> "Waiting"
        G1ServiceCommon.GlassesStatus.DISCONNECTED -> "Disconnected"
        G1ServiceCommon.GlassesStatus.CONNECTING -> "Connecting"
        G1ServiceCommon.GlassesStatus.CONNECTED -> "Connected"
        G1ServiceCommon.GlassesStatus.DISCONNECTING -> "Disconnecting"
        G1ServiceCommon.GlassesStatus.ERROR -> "Error"
    }

    private fun signalDescription(strength: Int?): String = when (strength) {
        null -> "Unknown"
        4 -> "Excellent (4/4)"
        3 -> "Good (3/4)"
        2 -> "Fair (2/4)"
        1 -> "Weak (1/4)"
        0 -> "Very weak (0/4)"
        else -> "Unknown"
    }

    private fun rssiDescription(rssi: Int?): String = rssi?.let { "$it dBm" } ?: "—"

    private fun showStatus(text: String) {
        logTelemetry(TelemetryLogType.INFO, "Status update: $text")
    }

    private fun showConnectionFailure() {
        Log.i(TAG, "TIP_SHOWN=CLOSE_EVEN")
        notify(UiMessage.Snackbar(FAILURE_MESSAGE))
        logTelemetry(
            TelemetryLogType.ERROR,
            "Connection attempt failed. Couldn’t find or connect to glasses."
        )
    }

    private fun showPermissionError() {
        notify(UiMessage.Snackbar(PERMISSION_MESSAGE))
        logTelemetry(
            TelemetryLogType.ERROR,
            "Bluetooth permission is required to continue"
        )
    }

    private fun clearStatus() {
        // No-op placeholder while status handling is disabled.
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
        logTelemetry(
            TelemetryLogType.INFO,
            "Auto reconnect attempt #${attempt.retryCount} for $name"
        )
        notify(UiMessage.AutoConnectTriggered(name))
        viewModelScope.launch {
            repository.connectGlasses(id)
        }
    }

    init {
        viewModelScope.launch {
            repository.getServiceStateFlow().collect { serviceState ->
                val previousSnapshot = latestServiceSnapshot
                latestServiceSnapshot = serviceState
                handleTelemetrySnapshotChange(previousSnapshot, serviceState)
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
        logTelemetry(
            TelemetryLogType.INFO,
            "Scheduling auto retry for ${glassesName(id)} in $RETRY_DELAY_SECONDS seconds"
        )
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
