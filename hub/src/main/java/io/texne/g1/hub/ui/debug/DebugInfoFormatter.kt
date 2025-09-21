package io.texne.g1.hub.ui.debug

import io.texne.g1.basis.client.G1ServiceCommon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

class DebugInfoFormatter @Inject constructor() {
    fun format(snapshot: DebugSnapshot): String {
        val builder = StringBuilder()
        builder.appendLine("G1 Hub Debug Snapshot")
        builder.appendLine("Captured: ${formatTimestamp(snapshot.timestampMillis)}")
        builder.appendLine()

        val os = snapshot.osInfo
        builder.appendLine("[Environment]")
        builder.appendLine("App version: ${os.appVersionName}")
        builder.appendLine("Device: ${os.manufacturer} ${os.model}")
        builder.appendLine("Android: ${os.release} (SDK ${os.sdkInt})")
        builder.appendLine()

        val bluetooth = snapshot.bluetoothInfo
        builder.appendLine("[Bluetooth]")
        val bluetoothStatus = when (bluetooth.enabled) {
            true -> "Enabled"
            false -> "Disabled"
            null -> "Unavailable"
        }
        builder.appendLine("Adapter: $bluetoothStatus")
        if (bluetooth.error != null) {
            builder.appendLine("Error: ${bluetooth.error}")
        }
        if (bluetooth.bondedDevices.isEmpty()) {
            builder.appendLine("Bonded devices: none")
        } else {
            builder.appendLine("Bonded devices:")
            bluetooth.bondedDevices.forEach { device ->
                builder.appendLine("  • ${device.name} (${device.address})")
            }
        }
        builder.appendLine()

        builder.appendLine("[Permissions]")
        snapshot.permissionStatuses.forEach { permission ->
            val statusLabel = if (permission.granted) "granted" else "denied"
            builder.appendLine("${permission.permission}: $statusLabel")
        }
        builder.appendLine()

        builder.appendLine("[Service]")
        val service = snapshot.service
        if (service == null) {
            builder.appendLine("No service snapshot available.")
        } else {
            builder.appendLine("Status: ${service.status}")
            if (service.glasses.isEmpty()) {
                builder.appendLine("Glasses: none detected")
            } else {
                builder.appendLine("Glasses:")
                service.glasses.forEach { glasses ->
                    builder.appendLine("- ${glasses.name} (${glasses.id})")
                    builder.appendLine("  status=${glasses.status} battery=${formatBattery(glasses.batteryPercentage)}")
                    builder.appendLine(
                        "  left=${glasses.leftStatus} (${formatBattery(glasses.leftBattery)}) " +
                            "right=${glasses.rightStatus} (${formatBattery(glasses.rightBattery)})"
                    )
                    builder.appendLine(
                        "  signal=${glasses.signalStrength?.toString() ?: "—"} " +
                            "rssi=${glasses.rssi?.let { "$it dBm" } ?: "—"}"
                    )
                }
            }
        }
        builder.appendLine()

        builder.appendLine("[Scan history]")
        if (snapshot.scanHistory.isEmpty()) {
            builder.appendLine("No scan history available.")
        } else {
            snapshot.scanHistory.forEach { entry ->
                val connected = if (entry.connectedNames.isEmpty()) {
                    "connected=—"
                } else {
                    "connected=${entry.connectedNames.joinToString()}"
                }
                builder.appendLine(
                    "- ${formatTimestamp(entry.timestampMillis)} • status=${entry.status} • " +
                        "discovered=${entry.discoveredCount} • $connected"
                )
            }
        }
        builder.appendLine()

        builder.appendLine("[Gesture history]")
        if (snapshot.gestureHistory.isEmpty()) {
            builder.appendLine("No trigger history available.")
        } else {
            snapshot.gestureHistory.forEach { entry ->
                builder.appendLine(
                    "- ${formatTimestamp(entry.timestampMillis)} • seq=${entry.sequence} • " +
                        "${entry.type}/${entry.side}"
                )
            }
        }
        builder.appendLine()

        builder.appendLine("[Error logs]")
        if (snapshot.errorLogs.isEmpty()) {
            builder.appendLine("No stored error logs.")
        } else {
            snapshot.errorLogs.forEach { (name, logs) ->
                builder.appendLine("- $name")
                if (logs.isEmpty()) {
                    builder.appendLine("  (no entries)")
                } else {
                    logs.forEach { logEntry ->
                        builder.appendLine("  • $logEntry")
                    }
                }
            }
        }

        return builder.toString().trimEnd()
    }

    fun formatTimestamp(millis: Long): String = TIMESTAMP_FORMATTER.format(Date(millis))

    fun buildFileName(timestampMillis: Long): String = "g1-debug-${FILE_NAME_FORMATTER.format(Date(timestampMillis))}.txt"

    private fun formatBattery(value: Int): String = if (value >= 0) "$value%" else "—"

    private companion object {
        private val TIMESTAMP_FORMATTER = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS z", Locale.US)
        private val FILE_NAME_FORMATTER = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }
}

data class DebugSnapshot(
    val timestampMillis: Long,
    val osInfo: OsInfo,
    val bluetoothInfo: BluetoothInfo,
    val permissionStatuses: List<PermissionStatus>,
    val service: ServiceSnapshot?,
    val scanHistory: List<ScanHistoryEntry>,
    val gestureHistory: List<GestureHistoryEntry>,
    val errorLogs: Map<String, List<String>>
) {
    data class OsInfo(
        val release: String,
        val sdkInt: Int,
        val manufacturer: String,
        val model: String,
        val appVersionName: String
    )

    data class BluetoothInfo(
        val enabled: Boolean?,
        val bondedDevices: List<BondedDevice>,
        val error: String?
    ) {
        data class BondedDevice(
            val name: String,
            val address: String
        )
    }

    data class PermissionStatus(
        val permission: String,
        val granted: Boolean
    )

    data class ServiceSnapshot(
        val status: G1ServiceCommon.ServiceStatus,
        val glasses: List<Glasses>
    ) {
        data class Glasses(
            val id: String,
            val name: String,
            val status: G1ServiceCommon.GlassesStatus,
            val batteryPercentage: Int,
            val leftStatus: G1ServiceCommon.GlassesStatus,
            val leftBattery: Int,
            val rightStatus: G1ServiceCommon.GlassesStatus,
            val rightBattery: Int,
            val signalStrength: Int?,
            val rssi: Int?
        )
    }

    data class ScanHistoryEntry(
        val timestampMillis: Long,
        val status: G1ServiceCommon.ServiceStatus,
        val discoveredCount: Int,
        val connectedNames: List<String>
    )

    data class GestureHistoryEntry(
        val sequence: Int,
        val timestampMillis: Long,
        val type: G1ServiceCommon.GestureType,
        val side: G1ServiceCommon.GestureSide
    )
}
