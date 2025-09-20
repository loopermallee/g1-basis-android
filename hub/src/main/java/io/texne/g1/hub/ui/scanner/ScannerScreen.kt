import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.texne.g1.basis.client.G1ServiceCommon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    scanning: Boolean,
    error: Boolean,
    nearbyGlasses: List<G1ServiceCommon.Glasses>?,
    availableLeftDevices: List<G1ServiceCommon.AvailableDevice>,
    availableRightDevices: List<G1ServiceCommon.AvailableDevice>,
    selectedLeftAddress: String?,
    selectedRightAddress: String?,
    scan: () -> Unit,
    onSelectLeft: (String?) -> Unit,
    onSelectRight: (String?) -> Unit,
    connectSelected: () -> Unit,
) {
    val pullToRefreshState = rememberPullToRefreshState()

    PullToRefreshBox(
        modifier = Modifier.fillMaxSize(),
        isRefreshing = scanning,
        onRefresh = scan,
        state = pullToRefreshState
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            when {
                error -> StatusText("An error occurred. Please try again.")
                scanning -> StatusText("Scanning for nearby glasses…")
                availableLeftDevices.isEmpty() && availableRightDevices.isEmpty() ->
                    StatusText("No devices were found nearby.")
            }

            DeviceSelectionSection(
                title = "Left devices",
                devices = availableLeftDevices,
                selectedAddress = selectedLeftAddress,
                onSelect = onSelectLeft
            )

            DeviceSelectionSection(
                title = "Right devices",
                devices = availableRightDevices,
                selectedAddress = selectedRightAddress,
                onSelect = onSelectRight
            )

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedLeftAddress != null && selectedRightAddress != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(6, 64, 43, 255),
                    contentColor = Color.White
                ),
                onClick = connectSelected
            ) {
                Text("Connect selected devices")
            }

            if (!nearbyGlasses.isNullOrEmpty()) {
                DetectedPairsSection(nearbyGlasses)
            }
        }
    }
}

@Composable
private fun StatusText(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun DeviceSelectionSection(
    title: String,
    devices: List<G1ServiceCommon.AvailableDevice>,
    selectedAddress: String?,
    onSelect: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (devices.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 2.dp
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    text = "No devices detected",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                devices.forEach { device ->
                    val isSelected = device.address == selectedAddress
                    DeviceSelectionItem(
                        device = device,
                        selected = isSelected,
                        onClick = { onSelect(if (isSelected) null else device.address) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceSelectionItem(
    device: G1ServiceCommon.AvailableDevice,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (selected) Color(6, 64, 43, 255) else MaterialTheme.colorScheme.outlineVariant),
        color = if (selected) Color(230, 244, 235) else MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(device.name, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(device.address, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DetectedPairsSection(glasses: List<G1ServiceCommon.Glasses>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Detected pairs", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        glasses.forEach { pair ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 2.dp
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(pair.name, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(pair.id, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(pair.status.label(), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

private fun G1ServiceCommon.GlassesStatus.label(): String = when (this) {
    G1ServiceCommon.GlassesStatus.UNINITIALIZED -> "Uninitialized"
    G1ServiceCommon.GlassesStatus.DISCONNECTED -> "Disconnected"
    G1ServiceCommon.GlassesStatus.CONNECTING -> "Connecting"
    G1ServiceCommon.GlassesStatus.CONNECTED -> "Connected"
    G1ServiceCommon.GlassesStatus.DISCONNECTING -> "Disconnecting"
    G1ServiceCommon.GlassesStatus.ERROR -> "Error"
}
