import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.texne.g1.basis.client.G1ServiceCommon
import io.texne.g1.hub.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    scanning: Boolean,
    error: Boolean,
    nearbyGlasses: List<G1ServiceCommon.Glasses>?,
    showTroubleshooting: Boolean,
    onDismissTroubleshooting: () -> Unit,
    onRequestTroubleshooting: () -> Unit,
    scan: () -> Unit,
    connect: (id: String) -> Unit
) {
    val pullToRefreshState = rememberPullToRefreshState()

    Box(modifier = Modifier.fillMaxSize()) {
        PullToRefreshBox(
            modifier = Modifier.fillMaxSize(),
            isRefreshing = scanning,
            onRefresh = scan,
            state = pullToRefreshState,
        ) {
            val isEmpty = nearbyGlasses.isNullOrEmpty()
            val bottomPadding = if (showTroubleshooting) 240.dp else 112.dp
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = if (isEmpty) Arrangement.Center else Arrangement.spacedBy(32.dp),
                contentPadding = PaddingValues(bottom = if (isEmpty) 0.dp else bottomPadding)
            ) {
                when {
                    nearbyGlasses.isNullOrEmpty().not() -> {
                        items(nearbyGlasses!!, key = { it.id }) { glasses ->
                            GlassesItem(
                                glasses,
                                { connect(glasses.id) }
                            )
                        }
                    }

                    scanning -> {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(stringResource(R.string.scanner_scanning_message))
                            }
                        }
                    }

                    error -> {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(stringResource(R.string.scanner_error_message))
                                TextButton(onClick = onRequestTroubleshooting) {
                                    Text(stringResource(R.string.scanner_help_button))
                                }
                            }
                        }
                    }

                    nearbyGlasses != null -> {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(stringResource(R.string.scanner_empty_message))
                                TextButton(onClick = onRequestTroubleshooting) {
                                    Text(stringResource(R.string.scanner_help_button))
                                }
                            }
                        }
                    }
                }
            }
        }

        if (!showTroubleshooting) {
            FilledTonalButton(
                onClick = onRequestTroubleshooting,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
            ) {
                Text(stringResource(R.string.scanner_help_button))
            }
        }

        AnimatedVisibility(
            visible = showTroubleshooting,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 24.dp),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            TroubleshootingPanel(
                modifier = Modifier.fillMaxWidth(),
                onDismiss = onDismissTroubleshooting
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun GlassesItem(
    glasses: G1ServiceCommon.Glasses,
    connect: () -> Unit
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
                    .padding(16.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().weight(1f)
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
                        Modifier.weight(1f).fillMaxHeight().padding(8.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        when {
                            glasses.status == G1ServiceCommon.GlassesStatus.CONNECTING || glasses.status == G1ServiceCommon.GlassesStatus.DISCONNECTING -> {
                                CircularProgressIndicator(
                                    color = Color.Black
                                )
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
                Row(
                    Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy((-8).dp)
                    ) {
                        Text(
                            text = glasses.name,
                            fontSize = 24.sp,
                            color = Color.Black,
                            fontWeight = FontWeight.Black
                        )
                        Text(glasses.id, fontSize = 10.sp, color = Color.Gray)
                    }
                }
            }
        }
    }
}

