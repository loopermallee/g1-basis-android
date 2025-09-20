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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.texne.g1.basis.client.G1ServiceCommon
import io.texne.g1.hub.ui.ApplicationViewModel

@Composable
fun TelemetryScreen(
    entries: List<ApplicationViewModel.TelemetryEntry>,
    onDisconnect: (String) -> Unit
) {
    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Telemetry will appear after scanning begins.", color = Color.Gray)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(entries, key = { it.id }) { entry ->
            TelemetryCard(entry = entry, onDisconnect = onDisconnect)
        }
    }
}

@Composable
private fun TelemetryCard(
    entry: ApplicationViewModel.TelemetryEntry,
    onDisconnect: (String) -> Unit
) {
    Box(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(entry.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    Text(entry.id, fontSize = 11.sp, color = Color.Gray)
                }
                if (entry.status == G1ServiceCommon.GlassesStatus.CONNECTED) {
                    Button(
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(169, 11, 11, 255),
                            contentColor = Color.White
                        ),
                        onClick = { onDisconnect(entry.id) }
                    ) {
                        Text("DISCONNECT")
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Status • ${statusLabel(entry.status)}", color = Color.Black)
                Text("Signal • ${signalLabel(entry.signalStrength)}", color = Color.Black)
                Text("RSSI • ${rssiLabel(entry.rssi)}", color = Color.Black)
                Text("Retry attempts • ${entry.retryCount}", color = Color.Black)
            }
        }
    }
}

private fun statusLabel(status: G1ServiceCommon.GlassesStatus): String = when (status) {
    G1ServiceCommon.GlassesStatus.UNINITIALIZED -> "Waiting"
    G1ServiceCommon.GlassesStatus.DISCONNECTED -> "Disconnected"
    G1ServiceCommon.GlassesStatus.CONNECTING -> "Connecting"
    G1ServiceCommon.GlassesStatus.CONNECTED -> "Connected"
    G1ServiceCommon.GlassesStatus.DISCONNECTING -> "Disconnecting"
    G1ServiceCommon.GlassesStatus.ERROR -> "Error"
}

private fun signalLabel(strength: Int?): String = when (strength) {
    null -> "Unknown"
    4 -> "Excellent (4/4)"
    3 -> "Good (3/4)"
    2 -> "Fair (2/4)"
    1 -> "Weak (1/4)"
    0 -> "Very weak (0/4)"
    else -> "Unknown"
}

private fun rssiLabel(rssi: Int?): String = rssi?.let { "$it dBm" } ?: "—"
