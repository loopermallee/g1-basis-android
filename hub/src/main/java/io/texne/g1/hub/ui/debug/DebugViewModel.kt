package io.texne.g1.hub.ui.debug

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.texne.g1.basis.client.G1ServiceCommon
import io.texne.g1.hub.BuildConfig
import io.texne.g1.hub.model.Repository
import javax.inject.Inject
import kotlin.collections.ArrayDeque
import kotlin.jvm.JvmSuppressWildcards
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.util.Locale

@HiltViewModel
class DebugViewModel @Inject constructor(
    private val application: Application,
    private val repository: Repository,
    private val logProviders: Set<@JvmSuppressWildcards DebugLogProvider>,
    private val formatter: DebugInfoFormatter
) : ViewModel() {

    data class DebugUiState(
        val formattedText: String = "",
        val exportFileName: String = "",
        val lastUpdatedLabel: String? = null,
        val autoRefreshEnabled: Boolean = false,
        val isRefreshing: Boolean = false,
        val message: DebugMessage? = null
    )

    data class DebugMessage(
        val text: String,
        val isError: Boolean = false
    )

    private val _state = MutableStateFlow(DebugUiState())
    val state: StateFlow<DebugUiState> = _state.asStateFlow()

    private var autoRefreshJob: kotlinx.coroutines.Job? = null
    private var latestServiceSnapshot: Repository.ServiceSnapshot? = null
    private val scanHistory = ArrayDeque<DebugSnapshot.ScanHistoryEntry>()
    private val gestureHistory = ArrayDeque<DebugSnapshot.GestureHistoryEntry>()
    private var lastRecordedStatus: G1ServiceCommon.ServiceStatus? = null

    init {
        observeRepository()
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { performRefresh() }
    }

    fun toggleAutoRefresh() {
        val enable = autoRefreshJob == null
        if (enable) {
            autoRefreshJob = viewModelScope.launch {
                emitMessage(DebugMessage("Auto-refresh enabled"))
                while (isActive) {
                    performRefresh()
                    delay(AUTO_REFRESH_INTERVAL_MILLIS)
                }
            }
        } else {
            autoRefreshJob?.cancel()
            autoRefreshJob = null
            emitMessage(DebugMessage("Auto-refresh disabled"))
        }
        _state.update { it.copy(autoRefreshEnabled = enable) }
    }

    fun copyToClipboard() {
        viewModelScope.launch {
            val text = state.value.formattedText
            if (text.isBlank()) {
                emitMessage(DebugMessage("No debug information to copy", isError = true))
                return@launch
            }
            try {
                val clipboard = ContextCompat.getSystemService(application, ClipboardManager::class.java)
                if (clipboard == null) {
                    emitMessage(DebugMessage("Clipboard service unavailable", isError = true))
                    return@launch
                }
                clipboard.setPrimaryClip(ClipData.newPlainText("G1 Hub debug", text))
                emitMessage(DebugMessage("Debug information copied to clipboard"))
            } catch (error: Exception) {
                Log.e(TAG, "Failed to copy debug info", error)
                emitMessage(
                    DebugMessage(
                        text = "Failed to copy debug info: ${error.message ?: error::class.java.simpleName}",
                        isError = true
                    )
                )
            }
        }
    }

    fun exportToFile() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentState = state.value
            val text = currentState.formattedText
            if (text.isBlank()) {
                emitMessage(DebugMessage("No debug information to export", isError = true))
                return@launch
            }
            try {
                val fileName = currentState.exportFileName.ifBlank {
                    formatter.buildFileName(System.currentTimeMillis())
                }
                val directory = application.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                    ?: application.filesDir
                if (!directory.exists()) {
                    directory.mkdirs()
                }
                val file = File(directory, fileName)
                file.writeText(text)
                emitMessage(DebugMessage("Exported to ${file.absolutePath}"))
            } catch (error: IOException) {
                Log.e(TAG, "Failed to export debug info", error)
                emitMessage(
                    DebugMessage(
                        text = "Failed to export debug info: ${error.message ?: error::class.java.simpleName}",
                        isError = true
                    )
                )
            }
        }
    }

    fun share() {
        viewModelScope.launch {
            val text = state.value.formattedText
            if (text.isBlank()) {
                emitMessage(DebugMessage("No debug information to share", isError = true))
                return@launch
            }
            try {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "G1 Hub debug report")
                    putExtra(Intent.EXTRA_TEXT, text)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val chooser = Intent.createChooser(shareIntent, "Share debug info").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                application.startActivity(chooser)
                emitMessage(DebugMessage("Share sheet opened"))
            } catch (error: Exception) {
                Log.e(TAG, "Failed to share debug info", error)
                emitMessage(
                    DebugMessage(
                        text = "Failed to share debug info: ${error.message ?: error::class.java.simpleName}",
                        isError = true
                    )
                )
            }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun observeRepository() {
        viewModelScope.launch {
            repository.getServiceStateFlow().collect { snapshot ->
                latestServiceSnapshot = snapshot
                val status = snapshot?.status
                if (status != null && status != lastRecordedStatus) {
                    lastRecordedStatus = status
                    recordScanHistory(status, snapshot)
                }
            }
        }
        viewModelScope.launch {
            repository.gestureEvents().collect { event ->
                recordGesture(event)
            }
        }
    }

    private fun emitMessage(message: DebugMessage) {
        _state.update { it.copy(message = message) }
    }

    private suspend fun performRefresh() {
        _state.update { it.copy(isRefreshing = true) }
        try {
            val snapshot = withContext(Dispatchers.IO) { buildDebugSnapshot() }
            val formatted = formatter.format(snapshot)
            val fileName = formatter.buildFileName(snapshot.timestampMillis)
            val lastUpdated = formatter.formatTimestamp(snapshot.timestampMillis)
            _state.update {
                it.copy(
                    formattedText = formatted,
                    exportFileName = fileName,
                    lastUpdatedLabel = lastUpdated,
                    isRefreshing = false
                )
            }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to refresh debug info", error)
            emitMessage(
                DebugMessage(
                    text = "Failed to refresh debug info: ${error.message ?: error::class.java.simpleName}",
                    isError = true
                )
            )
            _state.update { it.copy(isRefreshing = false) }
        }
    }

    private suspend fun buildDebugSnapshot(): DebugSnapshot {
        val serviceSnapshot = latestServiceSnapshot
        val timestamp = System.currentTimeMillis()
        val osInfo = DebugSnapshot.OsInfo(
            release = Build.VERSION.RELEASE ?: "Unknown",
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            appVersionName = BuildConfig.VERSION_NAME
        )
        val bluetoothInfo = loadBluetoothInfo()
        val permissions = gatherPermissionStatuses()
        val scanHistorySnapshot = scanHistory.toList()
        val gestureSnapshot = gestureHistory.toList()
        val errorLogs = loadErrorLogs()
        return DebugSnapshot(
            timestampMillis = timestamp,
            osInfo = osInfo,
            bluetoothInfo = bluetoothInfo,
            permissionStatuses = permissions,
            service = mapServiceSnapshot(serviceSnapshot),
            scanHistory = scanHistorySnapshot,
            gestureHistory = gestureSnapshot,
            errorLogs = errorLogs
        )
    }

    private fun recordScanHistory(
        status: G1ServiceCommon.ServiceStatus,
        snapshot: Repository.ServiceSnapshot?
    ) {
        val connected = snapshot?.glasses?.filter { it.status == G1ServiceCommon.GlassesStatus.CONNECTED }?.map { it.name }
            ?: emptyList()
        val entry = DebugSnapshot.ScanHistoryEntry(
            timestampMillis = System.currentTimeMillis(),
            status = status,
            discoveredCount = snapshot?.glasses?.size ?: 0,
            connectedNames = connected
        )
        scanHistory.addFirst(entry)
        while (scanHistory.size > HISTORY_LIMIT) {
            scanHistory.removeLast()
        }
    }

    private fun recordGesture(event: G1ServiceCommon.GestureEvent) {
        val entry = DebugSnapshot.GestureHistoryEntry(
            sequence = event.sequence,
            timestampMillis = event.timestampMillis,
            type = event.type,
            side = event.side
        )
        gestureHistory.addFirst(entry)
        while (gestureHistory.size > HISTORY_LIMIT) {
            gestureHistory.removeLast()
        }
    }

    private suspend fun loadErrorLogs(): Map<String, List<String>> {
        if (logProviders.isEmpty()) {
            return emptyMap()
        }
        val sortedProviders = logProviders.sortedBy { it.name.lowercase(Locale.ROOT) }
        val result = linkedMapOf<String, List<String>>()
        for (provider in sortedProviders) {
            try {
                result[provider.name] = provider.getLogs()
            } catch (error: Exception) {
                Log.e(TAG, "Failed to read logs from ${provider.name}", error)
                result[provider.name] = listOf(
                    "Failed to load logs: ${error.message ?: error::class.java.simpleName}"
                )
            }
        }
        return result
    }

    private fun gatherPermissionStatuses(): List<DebugSnapshot.PermissionStatus> {
        val permissions = mutableListOf<DebugSnapshot.PermissionStatus>()
        permissions += DebugSnapshot.PermissionStatus(
            permission = Manifest.permission.ACCESS_FINE_LOCATION,
            granted = ContextCompat.checkSelfPermission(
                application,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            ).forEach { permission ->
                permissions += DebugSnapshot.PermissionStatus(
                    permission = permission,
                    granted = ContextCompat.checkSelfPermission(application, permission) == PackageManager.PERMISSION_GRANTED
                )
            }
        }
        return permissions
    }

    @SuppressLint("MissingPermission")
    private fun loadBluetoothInfo(): DebugSnapshot.BluetoothInfo {
        val manager = ContextCompat.getSystemService(application, BluetoothManager::class.java)
        val adapter = manager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            return DebugSnapshot.BluetoothInfo(
                enabled = null,
                bondedDevices = emptyList(),
                error = "Bluetooth adapter unavailable"
            )
        }
        val enabled = adapter.isEnabled
        val bonded = mutableListOf<DebugSnapshot.BluetoothInfo.BondedDevice>()
        var errorMessage: String? = null
        val canReadBonded = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(application, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (canReadBonded) {
            try {
                val devices = adapter.bondedDevices ?: emptySet()
                devices.forEach { device ->
                    bonded += DebugSnapshot.BluetoothInfo.BondedDevice(
                        name = device.name ?: "(unknown)",
                        address = device.address
                    )
                }
            } catch (error: SecurityException) {
                Log.w(TAG, "Unable to access bonded devices", error)
                errorMessage = error.message ?: error::class.java.simpleName
            } catch (error: Exception) {
                Log.w(TAG, "Unexpected error while reading bonded devices", error)
                errorMessage = error.message ?: error::class.java.simpleName
            }
        } else {
            errorMessage = "Missing BLUETOOTH_CONNECT permission"
        }
        bonded.sortBy { it.name.lowercase(Locale.ROOT) }
        return DebugSnapshot.BluetoothInfo(
            enabled = enabled,
            bondedDevices = bonded,
            error = errorMessage
        )
    }

    private fun mapServiceSnapshot(snapshot: Repository.ServiceSnapshot?): DebugSnapshot.ServiceSnapshot? {
        return snapshot?.let {
            DebugSnapshot.ServiceSnapshot(
                status = it.status,
                glasses = it.glasses.map { glasses ->
                    DebugSnapshot.ServiceSnapshot.Glasses(
                        id = glasses.id,
                        name = glasses.name,
                        status = glasses.status,
                        batteryPercentage = glasses.batteryPercentage,
                        leftStatus = glasses.left.status,
                        leftBattery = glasses.left.batteryPercentage,
                        rightStatus = glasses.right.status,
                        rightBattery = glasses.right.batteryPercentage,
                        signalStrength = glasses.signalStrength,
                        rssi = glasses.rssi,
                        leftMac = glasses.leftMacAddress,
                        rightMac = glasses.rightMacAddress,
                        leftMtu = glasses.leftNegotiatedMtu,
                        rightMtu = glasses.rightNegotiatedMtu,
                        leftLastAttempt = glasses.leftLastConnectionAttemptMillis,
                        rightLastAttempt = glasses.rightLastConnectionAttemptMillis,
                        leftLastSuccess = glasses.leftLastConnectionSuccessMillis,
                        rightLastSuccess = glasses.rightLastConnectionSuccessMillis,
                        leftLastDisconnect = glasses.leftLastDisconnectMillis,
                        rightLastDisconnect = glasses.rightLastDisconnectMillis,
                        lastAttempt = glasses.lastConnectionAttemptMillis,
                        lastSuccess = glasses.lastConnectionSuccessMillis,
                        lastDisconnect = glasses.lastDisconnectMillis
                    )
                },
                lastConnectedId = it.lastConnectedId,
                scanTriggers = it.scanTriggerTimestamps,
                recentScanResults = it.recentScanResults.map { result ->
                    DebugSnapshot.ServiceSnapshot.ScanResult(
                        id = result.id,
                        name = result.name,
                        signalStrength = result.signalStrength,
                        rssi = result.rssi,
                        timestampMillis = result.timestampMillis
                    )
                }
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    companion object {
        private const val TAG = "DebugViewModel"
        private const val AUTO_REFRESH_INTERVAL_MILLIS = 5_000L
        private const val HISTORY_LIMIT = 20
    }
}
