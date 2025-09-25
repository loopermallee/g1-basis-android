package io.texne.g1.hub.ui.telemetry

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.texne.g1.basis.client.G1ServiceCommon
import io.texne.g1.hub.ui.ApplicationViewModel
import java.text.DateFormat
import java.util.Date

@Composable
fun TelemetryScreen(
    entries: List<ApplicationViewModel.TelemetryEntry>,
    logs: List<ApplicationViewModel.TelemetryLogEntry>,
    serviceStatus: G1ServiceCommon.ServiceStatus,
    onDisconnect: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceBackground),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            ServiceStatusCard(serviceStatus = serviceStatus)
        }

        if (entries.isEmpty()) {
            item { EmptyTelemetryCard() }
        } else {
            items(entries, key = { it.id }) { entry ->
                TelemetryCard(entry = entry, onDisconnect = onDisconnect)
            }
        }

        item {
            Text(
                text = "Event log",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = PrimaryText
            )
        }

        if (logs.isEmpty()) {
            item { EmptyLogCard() }
        } else {
            items(logs.asReversed(), key = { it.id }) { logEntry ->
                TelemetryLogRow(entry = logEntry)
            }
        }
    }
}

@Composable
private fun ServiceStatusCard(serviceStatus: G1ServiceCommon.ServiceStatus) {
    val info = serviceStatusInfo(serviceStatus)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackground, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Service status",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = SecondaryText
        )
        StatusBadge(StyledValue(info.label, info.color))
        Text(
            text = info.description,
            fontSize = 12.sp,
            color = SecondaryText
        )
    }
}

@Composable
private fun EmptyTelemetryCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackground, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "No telemetry yet",
            fontWeight = FontWeight.SemiBold,
            color = PrimaryText
        )
        Text(
            text = "Start scanning or connecting to glasses to see live telemetry.",
            fontSize = 12.sp,
            color = SecondaryText,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun EmptyLogCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackground, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "No events yet",
            fontWeight = FontWeight.SemiBold,
            color = PrimaryText
        )
        Text(
            text = "Actions, connection attempts, and telemetry updates will appear here.",
            fontSize = 12.sp,
            color = SecondaryText,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun TelemetryCard(
    entry: ApplicationViewModel.TelemetryEntry,
    onDisconnect: (String) -> Unit
) {
    val mainStatus = statusBadgeValue(entry.status)
    val leftStatus = statusBadgeValue(entry.leftStatus, prefix = "Left")
    val rightStatus = statusBadgeValue(entry.rightStatus, prefix = "Right")
    val signal = signalDisplay(entry.signalStrength)
    val rssi = rssiDisplay(entry.rssi)
    val retry = retryDisplay(entry.retryCount)
    val battery = batteryDisplay(entry.batteryPercentage)
    val leftBattery = batteryDisplay(entry.leftBatteryPercentage)
    val rightBattery = batteryDisplay(entry.rightBatteryPercentage)
    val lastUpdated = remember(entry.lastUpdatedAt) {
        formatTimestamp(entry.lastUpdatedAt)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackground, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(entry.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PrimaryText)
                Text(entry.id, fontSize = 12.sp, color = SecondaryText)
            }
            StatusBadge(mainStatus)
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Lens status",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = SecondaryText
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusBadge(leftStatus)
                StatusBadge(rightStatus)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricRow(label = "Signal", value = signal)
                MetricRow(label = "RSSI", value = rssi)
                MetricRow(label = "Retry attempts", value = retry)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricRow(label = "Battery (overall)", value = battery)
                MetricRow(label = "Battery (left)", value = leftBattery)
                MetricRow(label = "Battery (right)", value = rightBattery)
            }
        }

        Text(
            text = "Last update • $lastUpdated",
            fontSize = 12.sp,
            color = SecondaryText
        )

        if (entry.status == G1ServiceCommon.GlassesStatus.CONNECTED) {
            Button(
                colors = ButtonDefaults.buttonColors(
                    containerColor = ErrorRed,
                    contentColor = Color.White
                ),
                onClick = { onDisconnect(entry.id) }
            ) {
                Text("DISCONNECT", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: StyledValue) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 12.sp, color = SecondaryText)
        Text(
            text = value.text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = value.color,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun StatusBadge(value: StyledValue) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(value.color.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = value.text,
            color = value.color,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun TelemetryLogRow(entry: ApplicationViewModel.TelemetryLogEntry) {
    val timestamp = remember(entry.timestampMillis) { formatTimestamp(entry.timestampMillis) }
    val color = logColor(entry.type)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackground, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(timestamp, fontSize = 12.sp, color = SecondaryText)
        }
        Text(
            text = entry.message,
            color = color,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

private data class StyledValue(val text: String, val color: Color)

private data class ServiceStatusInfo(val label: String, val description: String, val color: Color)

private fun serviceStatusInfo(status: G1ServiceCommon.ServiceStatus): ServiceStatusInfo = when (status) {
    G1ServiceCommon.ServiceStatus.READY -> ServiceStatusInfo(
        label = "Ready",
        description = "Service ready for requests.",
        color = SuccessGreen
    )
    G1ServiceCommon.ServiceStatus.LOOKING -> ServiceStatusInfo(
        label = "Scanning",
        description = "Actively searching for nearby glasses.",
        color = InfoBlue
    )
    G1ServiceCommon.ServiceStatus.LOOKED -> ServiceStatusInfo(
        label = "Scan completed",
        description = "Last scan finished; waiting for telemetry.",
        color = InfoBlue
    )
    G1ServiceCommon.ServiceStatus.PERMISSION_REQUIRED -> ServiceStatusInfo(
        label = "Permission required",
        description = "Grant Bluetooth permissions to continue.",
        color = ErrorRed
    )
    G1ServiceCommon.ServiceStatus.ERROR -> ServiceStatusInfo(
        label = "Error",
        description = "Service reported an error state.",
        color = ErrorRed
    )
}

private fun statusDisplay(status: G1ServiceCommon.GlassesStatus): StyledValue = when (status) {
    G1ServiceCommon.GlassesStatus.UNINITIALIZED -> StyledValue("Waiting", NeutralColor)
    G1ServiceCommon.GlassesStatus.DISCONNECTED -> StyledValue("Disconnected", NeutralColor)
    G1ServiceCommon.GlassesStatus.CONNECTING -> StyledValue("Connecting", InfoBlue)
    G1ServiceCommon.GlassesStatus.CONNECTED -> StyledValue("Connected", SuccessGreen)
    G1ServiceCommon.GlassesStatus.DISCONNECTING -> StyledValue("Disconnecting", WarningAmber)
    G1ServiceCommon.GlassesStatus.ERROR -> StyledValue("Error", ErrorRed)
}

private fun statusBadgeValue(
    status: G1ServiceCommon.GlassesStatus,
    prefix: String? = null
): StyledValue {
    val base = statusDisplay(status)
    val text = prefix?.let { "$it • ${base.text}" } ?: base.text
    return StyledValue(text, base.color)
}

private fun signalDisplay(strength: Int?): StyledValue = when (strength) {
    null -> StyledValue("Unknown", NeutralColor)
    4 -> StyledValue("Excellent (4/4)", SuccessGreen)
    3 -> StyledValue("Good (3/4)", SuccessGreen)
    2 -> StyledValue("Fair (2/4)", WarningAmber)
    1 -> StyledValue("Weak (1/4)", ErrorRed)
    0 -> StyledValue("Very weak (0/4)", ErrorRed)
    else -> StyledValue("Unknown", NeutralColor)
}

private fun rssiDisplay(rssi: Int?): StyledValue = when {
    rssi == null -> StyledValue("—", NeutralColor)
    rssi >= -60 -> StyledValue("$rssi dBm (strong)", SuccessGreen)
    rssi >= -75 -> StyledValue("$rssi dBm (moderate)", WarningAmber)
    else -> StyledValue("$rssi dBm (weak)", ErrorRed)
}

private fun batteryDisplay(percentage: Int): StyledValue = when {
    percentage >= 60 -> StyledValue("$percentage%", SuccessGreen)
    percentage >= 30 -> StyledValue("$percentage%", WarningAmber)
    else -> StyledValue("$percentage%", ErrorRed)
}

private fun retryDisplay(retryCount: Int): StyledValue =
    if (retryCount == 0) {
        StyledValue("0 (no retries)", SuccessGreen)
    } else {
        val suffix = if (retryCount == 1) "attempt" else "attempts"
        StyledValue("$retryCount $suffix", ErrorRed)
    }

private fun logColor(type: ApplicationViewModel.TelemetryLogType): Color = when (type) {
    ApplicationViewModel.TelemetryLogType.INFO -> InfoBlue
    ApplicationViewModel.TelemetryLogType.SUCCESS -> SuccessGreen
    ApplicationViewModel.TelemetryLogType.ERROR -> ErrorRed
}

private fun formatTimestamp(timestampMillis: Long): String =
    DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(timestampMillis))

private val PrimaryText = Color(0xFF111827)
private val SecondaryText = Color(0xFF6B7280)
private val NeutralColor = Color(0xFF374151)
private val SuccessGreen = Color(0xFF2E7D32)
private val ErrorRed = Color(0xFFC62828)
private val InfoBlue = Color(0xFF1565C0)
private val WarningAmber = Color(0xFFF9A825)
private val SurfaceBackground = Color(0xFFF3F4F6)
private val CardBackground = Color.White
