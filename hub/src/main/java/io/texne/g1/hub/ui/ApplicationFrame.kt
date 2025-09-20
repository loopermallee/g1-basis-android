package io.texne.g1.hub.ui

import GlassesScreen
import ScannerScreen
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import io.texne.g1.hub.ui.chat.ChatScreen
import io.texne.g1.hub.ui.settings.SettingsScreen
import io.texne.g1.hub.ui.telemetry.TelemetryScreen

@Composable
fun ApplicationFrame(snackbarHostState: SnackbarHostState) {
    val viewModel = hiltViewModel<ApplicationViewModel>()
    val state by viewModel.state.collectAsState()
    val selectedSection = state.selectedSection

    LaunchedEffect(viewModel) {
        viewModel.uiMessages.collect { message ->
            val text = when (message) {
                is ApplicationViewModel.UiMessage.AutoConnectTriggered ->
                    "Auto-connecting to ${message.glassesName}"
                is ApplicationViewModel.UiMessage.AutoConnectFailed ->
                    "Auto-connect failed for ${message.glassesName}"
            }
            snackbarHostState.showSnackbar(text)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Header(
            selectedSection = selectedSection,
            onSectionSelected = viewModel::selectSection
        )

        val connectedGlasses = state.connectedGlasses

        when (selectedSection) {
            AppSection.GLASSES -> {
                if (connectedGlasses != null) {
                    GlassesScreen(
                        connectedGlasses,
                        { viewModel.disconnect(connectedGlasses.id) }
                    )
                } else {
                    ScannerScreen(
                        scanning = state.scanning,
                        error = state.error,
                        serviceStatus = state.serviceStatus,
                        nearbyGlasses = state.nearbyGlasses,
                        retryCountdowns = state.retryCountdowns,
                        scan = { viewModel.scan() },
                        connect = { viewModel.connect(it) },
                        disconnect = { viewModel.disconnect(it) },
                        cancelRetry = { viewModel.cancelAutoRetry(it) },
                        retryNow = { viewModel.retryNow(it) }
                    )
                }
            }
            AppSection.TELEMETRY -> {
                TelemetryScreen(
                    entries = state.telemetryEntries,
                    onDisconnect = viewModel::disconnect
                )
            }
            AppSection.ASSISTANT -> {
                ChatScreen(
                    connectedGlassesName = connectedGlasses?.name,
                    onNavigateToSettings = { viewModel.selectSection(AppSection.SETTINGS) }
                )
            }
            AppSection.SETTINGS -> {
                SettingsScreen()
            }
        }
    }
}

@Composable
fun Header(
    selectedSection: AppSection,
    onSectionSelected: (AppSection) -> Unit
) {
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(128.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy((-8).dp)
            ) {
                Row {
                    Image(
                        painter = painterResource(io.texne.g1.basis.service.R.mipmap.ic_service_foreground),
                        contentDescription = "G1 Hub Logo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.height(40.dp).width(32.dp)
                    )
                    Text(
                        "G1",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.Gray,
                        fontStyle = FontStyle.Italic
                    )
                    Text("Hub", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                }
                Text("A hub for Basis applications", fontSize = 11.sp)
            }
        }

        TabRow(selectedTabIndex = selectedSection.ordinal) {
            AppSection.entries.forEach { section ->
                Tab(
                    selected = section == selectedSection,
                    onClick = { onSectionSelected(section) },
                    text = { Text(section.label) }
                )
            }
        }
    }
}

enum class AppSection(val label: String) {
    GLASSES("Glasses"),
    TELEMETRY("Telemetry"),
    ASSISTANT("Assistant"),
    SETTINGS("Settings")
}
