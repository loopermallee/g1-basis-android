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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import io.texne.g1.hub.ui.ApplicationViewModel
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    scanning: Boolean,
    error: Boolean,
    serviceStatus: G1ServiceCommon.ServiceStatus,
    nearbyGlasses: List<GlassesSnapshot>?,
    retryCountdowns: Map<String, ApplicationViewModel.RetryCountdown>,
    statusMessage: String?,
    errorMessage: String?,
    scan: () -> Unit,
    connect: (id: String) -> Unit,
    disconnect: (id: String) -> Unit,
    cancelRetry: (id: String) -> Unit,
    retryNow: (id: String) -> Unit,
    onBondedConnect: () -> Unit
) {
    val pullToRefreshState = rememberPullToRefreshState()

    PullToRefreshBox(
        modifier = Modifier.fillMaxSize(),
        isRefreshing = scanning,
        onRefresh = scan,
        state = pullToRefreshState,
    ) {
        Column(Modifier.fillMaxSize()) {
            ServiceStatusBanner(
                status = serviceStatus,
                onRetry = scan
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = if (
                    nearbyGlasses.isNullOrEmpty()
                ) Arrangement.Center else Arrangement.spacedBy(32.dp)
            ) {
                if (!statusMessage.isNullOrEmpty()) {
                    item {
                        StatusMessageCard(statusMessage)
                    }
                }
                if (!errorMessage.isNullOrEmpty()) {
                    item {
                        ErrorMessageCard(errorMessage, onBondedConnect)
                    }
                }
            when {
                nearbyGlasses.isNullOrEmpty().not() -> {
                    items(nearbyGlasses!!.size) { index ->
                        val glasses = nearbyGlasses[index]
                        GlassesItem(
                            glasses = glasses,
                            retryCountdown = retryCountdowns[glasses.id],
                            connect = { connect(glasses.id) },
                            disconnect = { disconnect(glasses.id) },
                            cancelRetry = { cancelRetry(glasses.id) },
                            retryNow = { retryNow(glasses.id) }
                        )
                    }
                }

                scanning -> {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Scanning for nearby glasses...")
                        }
                    }
                }

                error -> {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("An error ocurred. Please try again.")
                        }
                    }
                }

                nearbyGlasses != null -> {
                    item {
                        NoDevicesFoundCard(onBondedConnect)
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun ServiceStatusBanner(
    status: G1ServiceCommon.ServiceStatus,
    onRetry: () -> Unit
) {
    when (status) {
        G1ServiceCommon.ServiceStatus.LOOKING -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp)
                        Text(
                            text = "Looking for nearby glasses…",
                            fontWeight = FontWeight.SemiBold,
                            color = Color.Gray
                        )
                    }
                    Text(
                        text = "Tip: If you paired via the Even app, we’ll try your bonded device first.",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
            }
        }
        G1ServiceCommon.ServiceStatus.ERROR -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Could not connect to the service",
                            fontWeight = FontWeight.SemiBold,
                            color = Color.Black
                        )
                        Text(
                            text = "Check Bluetooth and try again.",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                    Button(
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(169, 11, 11, 255),
                            contentColor = Color.White
                        ),
                        onClick = onRetry
                    ) {
                        Text("RETRY")
                    }
                }
            }
        }
        else -> {}
    }
}

@Composable
private fun StatusMessageCard(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = message,
                fontWeight = FontWeight.SemiBold,
                color = Color.Black
            )
            Text(
                text = "We’ll automatically stop scanning once we connect.",
                fontSize = 12.sp,
                color = Color.Gray
            )
        }
    }
}

@Composable
private fun ErrorMessageCard(
    message: String,
    onBondedConnect: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = message,
                fontWeight = FontWeight.SemiBold,
                color = Color.Black
            )
            Button(
                onClick = onBondedConnect,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Black,
                    contentColor = Color.White
                )
            ) {
                Text("Try Bonded Connect")
            }
        }
    }
}

@Composable
private fun NoDevicesFoundCard(onBondedConnect: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .background(Color.White, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "No glasses were found nearby.",
                fontWeight = FontWeight.SemiBold,
                color = Color.Black
            )
            Text(
                text = "Tip: Unfold glasses, close the Even app, toggle Bluetooth, and retry.",
                fontSize = 12.sp,
                color = Color.Gray
            )
            Button(
                onClick = onBondedConnect,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Black,
                    contentColor = Color.White
                )
            ) {
                Text("Try Bonded Connect")
            }
        }
    }
}

@Composable
fun GlassesItem(
    glasses: GlassesSnapshot,
    retryCountdown: ApplicationViewModel.RetryCountdown?,
    connect: () -> Unit,
    disconnect: () -> Unit,
    cancelRetry: () -> Unit,
    retryNow: () -> Unit
) {
    Box(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Box(
            Modifier.fillMaxWidth()
                .background(Color.White, RoundedCornerShape(16.dp))
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2.5f)
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    Modifier.fillMaxWidth(),
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
                        Modifier.weight(1f).padding(8.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        when {
                            glasses.status == G1ServiceCommon.GlassesStatus.CONNECTING || glasses.status == G1ServiceCommon.GlassesStatus.DISCONNECTING -> {
                                CircularProgressIndicator(
                                    color = Color.Black
                                )
                            }

                            retryCountdown != null -> {
                                val nextAttemptText = remember(retryCountdown.nextAttemptAtMillis) {
                                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(retryCountdown.nextAttemptAtMillis))
                                }
                                Column(
                                    horizontalAlignment = Alignment.End,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Retrying in ${retryCountdown.secondsRemaining}s",
                                        fontSize = 12.sp,
                                        color = Color.Gray,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Next attempt at $nextAttemptText",
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(224, 224, 224, 255),
                                                contentColor = Color.Black
                                            ),
                                            onClick = cancelRetry
                                        ) {
                                            Text("CANCEL")
                                        }
                                        Button(
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(6, 64, 43, 255),
                                                contentColor = Color.White
                                            ),
                                            onClick = retryNow
                                        ) {
                                            Text("RETRY NOW")
                                        }
                                    }
                                }
                            }

                            glasses.status == G1ServiceCommon.GlassesStatus.CONNECTED -> {
                                Button(
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(169, 11, 11, 255),
                                        contentColor = Color.White
                                    ),
                                    onClick = disconnect
                                ) {
                                    Text("DISCONNECT")
                                }
                            }

                            glasses.status != G1ServiceCommon.GlassesStatus.CONNECTED -> {
                                Button(
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(6, 64, 43, 255),
                                        contentColor = Color.White
                                    ),
                                    onClick = { connect() }
                                ) {
                                    Text("CONNECT")
                                }
                            }

                            else -> {
                            }
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
        val transition = rememberInfiniteTransition(label = "scannerStatusPulse")
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

