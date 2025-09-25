package io.texne.g1.hub.ui

import GlassesScreen
import ScannerScreen
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import io.texne.g1.basis.client.G1ServiceCommon
import io.texne.g1.hub.permissions.PermissionHelper
import io.texne.g1.hub.ui.chat.ChatScreen
import io.texne.g1.hub.ui.settings.SettingsScreen
import io.texne.g1.hub.ui.telemetry.TelemetryScreen

@Composable
fun ApplicationFrame(snackbarHostState: SnackbarHostState) {
    val viewModel = hiltViewModel<ApplicationViewModel>()
    val state = viewModel.state.collectAsState().value
    val selectedSection = state.selectedSection
    val context = LocalContext.current
    var pendingPermissionAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var autoPromptShown by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val action = pendingPermissionAction
        pendingPermissionAction = null
        if (result.resultCode == Activity.RESULT_OK) {
            action?.invoke()
        } else {
            viewModel.onPermissionDenied()
        }
    }

    val runWithPermissions: ((() -> Unit) -> Unit) = remember(context, viewModel, permissionLauncher) {
        { action ->
            val intent = PermissionHelper.createPermissionIntent(context)
            if (intent == null) {
                action()
            } else if (pendingPermissionAction == null) {
                pendingPermissionAction = action
                permissionLauncher.launch(intent)
            }
        }
    }

    val scanAction = remember(viewModel, runWithPermissions) {
        {
            runWithPermissions { viewModel.scan() }
        }
    }

    val bondedConnectAction = remember(viewModel, runWithPermissions) {
        {
            runWithPermissions { viewModel.tryBondedConnect() }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.uiMessages.collect { message ->
            val text = when (message) {
                is ApplicationViewModel.UiMessage.AutoConnectTriggered ->
                    "Auto-connecting to ${message.glassesName}"
                is ApplicationViewModel.UiMessage.AutoConnectFailed ->
                    "Auto-connect failed for ${message.glassesName}"
                is ApplicationViewModel.UiMessage.Snackbar -> message.text
            }
            snackbarHostState.showSnackbar(text)
        }
    }

    LaunchedEffect(state.serviceStatus) {
        if (state.serviceStatus == G1ServiceCommon.ServiceStatus.PERMISSION_REQUIRED) {
            if (!autoPromptShown) {
                autoPromptShown = true
                runWithPermissions { }
            }
        } else {
            autoPromptShown = false
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

        Box(modifier = Modifier.weight(1f)) {
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
                            glasses = state.glasses,
                            retryCountdowns = state.retryCountdowns,
                            status = state.status,
                            errorMessage = state.errorMessage,
                            scan = scanAction,
                            connect = { viewModel.connect(it) },
                            disconnect = { viewModel.disconnect(it) },
                            cancelRetry = { viewModel.cancelAutoRetry(it) },
                            retryNow = { viewModel.retryNow(it) },
                            onBondedConnect = bondedConnectAction
                        )
                    }
                }
                AppSection.TELEMETRY -> {
                    TelemetryScreen(
                        entries = state.telemetryEntries,
                        logs = state.telemetryLogs,
                        serviceStatus = state.serviceStatus,
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
                    SettingsScreen(modifier = Modifier.fillMaxSize())
                }
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
