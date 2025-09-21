import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.texne.g1.basis.client.G1ServiceCommon
import io.texne.g1.hub.R
import io.texne.g1.hub.model.Repository.GlassesSnapshot

@Composable
fun GlassesScreen(
    glasses: GlassesSnapshot,
    disconnect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.weight(1f)) {
                        Image(
                            modifier = Modifier
                                .padding(8.dp),
                            painter = painterResource(R.drawable.glasses_a),
                            contentDescription = "Image of glasses"
                        )
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .padding(8.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Button(
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(169, 11, 11, 255),
                                contentColor = Color.White
                            ),
                            onClick = { disconnect() }
                        ) {
                            Text("DISCONNECT")
                        }
                    }
                }
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = glasses.name,
                        fontSize = 24.sp,
                        color = Color.Black,
                        fontWeight = FontWeight.Black
                    )
                    Text(glasses.id, fontSize = 10.sp, color = Color.Gray)
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    EyeStatusRow(
                        label = "Left temple",
                        status = glasses.left.status,
                        batteryPercentage = glasses.left.batteryPercentage
                    )
                    EyeStatusRow(
                        label = "Right temple",
                        status = glasses.right.status,
                        batteryPercentage = glasses.right.batteryPercentage
                    )
                    Crossfade(targetState = glasses.status to glasses.batteryPercentage, label = "overallStatus") { (status, battery) ->
                        Text(
                            text = "Overall • ${statusLabel(status)} • ${batteryLabel(battery)}",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EyeStatusRow(
    label: String,
    status: G1ServiceCommon.GlassesStatus,
    batteryPercentage: Int
) {
    val pulsing = status == G1ServiceCommon.GlassesStatus.CONNECTING || status == G1ServiceCommon.GlassesStatus.DISCONNECTING
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "eyeStatusPulse")
        val value by transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = { it }),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseAlpha"
        )
        value
    } else {
        1f
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Crossfade(targetState = status, label = "statusIcon") { targetStatus ->
            Text(
                text = statusIcon(targetStatus),
                fontSize = 24.sp,
                modifier = Modifier.alpha(alpha)
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.Black)
            Crossfade(targetState = status to batteryPercentage, label = "statusText") { (targetStatus, targetBattery) ->
                Text(
                    text = "${statusLabel(targetStatus)} • ${batteryLabel(targetBattery)}",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        }
    }
}

private fun statusIcon(status: G1ServiceCommon.GlassesStatus) = when (status) {
    G1ServiceCommon.GlassesStatus.CONNECTED -> "✅"
    G1ServiceCommon.GlassesStatus.CONNECTING,
    G1ServiceCommon.GlassesStatus.DISCONNECTING -> "🔄"
    else -> "❌"
}

private fun statusLabel(status: G1ServiceCommon.GlassesStatus) = when (status) {
    G1ServiceCommon.GlassesStatus.UNINITIALIZED -> "Waiting"
    G1ServiceCommon.GlassesStatus.DISCONNECTED -> "Disconnected"
    G1ServiceCommon.GlassesStatus.CONNECTING -> "Connecting"
    G1ServiceCommon.GlassesStatus.CONNECTED -> "Connected"
    G1ServiceCommon.GlassesStatus.DISCONNECTING -> "Disconnecting"
    G1ServiceCommon.GlassesStatus.ERROR -> "Error"
}

private fun batteryLabel(battery: Int) = if (battery >= 0) "$battery%" else "—"