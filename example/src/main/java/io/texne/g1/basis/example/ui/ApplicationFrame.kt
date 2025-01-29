package io.texne.g1.basis.example.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import io.texne.g1.basis.example.ui.scanner.GlassesScanner
import java.util.Locale

@Composable
fun ApplicationFrame() {
    val connectedGlasses = hiltViewModel<ApplicationFrameViewModel>().connectedGlasses.collectAsState().value
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = selectedTab
        ) {
            Box(
                modifier = Modifier.height(64.dp).fillMaxSize().clickable(onClick = { selectedTab = 0 }),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Nearby Devices"
                )
            }
            connectedGlasses.forEachIndexed { index, glasses ->
                Box(
                    modifier = Modifier.height(64.dp).fillMaxSize().clickable(onClick = { selectedTab = index+1 }),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(-4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(glasses.name)
                        if(glasses.batteryPercentage >= 0) {
                            Text(
                                color = when {
                                    glasses.batteryPercentage > 74 -> Color.Green
                                    glasses.batteryPercentage > 24 -> Color.Yellow
                                    else -> Color.Red
                                },
                                text = String.format(Locale.US, "%3d%% battery", glasses.batteryPercentage)
                            )
                        }
                    }
                }
            }
        }
        Box(
            modifier = Modifier.weight(1f)
        ) {
            when(selectedTab) {
                0 -> {
                    GlassesScanner()
                }
                else -> {
                    Text("DEVICE")
                }
            }
        }
    }
}