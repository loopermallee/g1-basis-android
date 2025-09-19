package io.texne.g1.hub.ui

import GlassesScreen
import ScannerScreen
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.texne.g1.hub.ui.chat.ChatScreen
import io.texne.g1.hub.ui.home.HomeScreen
import io.texne.g1.hub.ui.home.HubPalette
import io.texne.g1.hub.ui.settings.SettingsScreen
import io.texne.g1.hub.ui.todo.TodoScreen

@Composable
fun ApplicationFrame() {
    val viewModel = hiltViewModel<ApplicationViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    var destination by rememberSaveable(stateSaver = AppDestination.saver()) {
        mutableStateOf<AppDestination>(AppDestination.Home)
    }

    Scaffold(
        containerColor = HubPalette.Background,
        topBar = {
            HubTopAppBar(
                destination = destination,
                onNavigateHome = { destination = AppDestination.Home }
            )
        }
    ) { innerPadding ->
        DestinationContent(
            destination = destination,
            state = state,
            onDestinationSelected = { destination = it },
            onScan = viewModel::scan,
            onConnect = viewModel::connect,
            onDisconnect = viewModel::disconnect,
            contentPadding = innerPadding
        )
    }
}

@Composable
private fun DestinationContent(
    destination: AppDestination,
    state: ApplicationViewModel.State?,
    onDestinationSelected: (AppDestination) -> Unit,
    onScan: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    val connectedGlasses = state?.connectedGlasses

    when (destination) {
        AppDestination.Home -> {
            HomeScreen(
                modifier = Modifier.padding(contentPadding),
                onAssistantClick = { onDestinationSelected(AppDestination.Assistant) },
                onSettingsClick = { onDestinationSelected(AppDestination.Settings) },
                onSubtitlesClick = { onDestinationSelected(AppDestination.Subtitles) },
                onTodoClick = { onDestinationSelected(AppDestination.Todo) },
                onNavigationClick = { onDestinationSelected(AppDestination.Navigation) },
                onEReaderClick = { onDestinationSelected(AppDestination.EReader) },
                onNotificationsClick = { onDestinationSelected(AppDestination.Notifications) }
            )
        }

        AppDestination.Assistant -> {
            ChatScreen(
                connectedGlassesName = connectedGlasses?.name,
                onNavigateToSettings = { onDestinationSelected(AppDestination.Settings) },
                modifier = Modifier
                    .padding(contentPadding)
                    .background(HubPalette.Background)
            )
        }

        AppDestination.Settings -> {
            SettingsDestination(
                modifier = Modifier.padding(contentPadding),
                state = state,
                onScan = onScan,
                onConnect = onConnect,
                onDisconnect = onDisconnect
            )
        }

        AppDestination.Subtitles -> {
            SubtitlesDestination(
                modifier = Modifier.padding(contentPadding)
            )
        }

        AppDestination.Todo -> {
            TodoScreen(
                modifier = Modifier
                    .padding(contentPadding)
                    .background(HubPalette.Background)
            )
        }

        AppDestination.Navigation -> {
            ComingSoonScreen(
                modifier = Modifier.padding(contentPadding),
                title = "Navigation"
            )
        }

        AppDestination.EReader -> {
            ComingSoonScreen(
                modifier = Modifier.padding(contentPadding),
                title = "E-Reader"
            )
        }

        AppDestination.Notifications -> {
            ComingSoonScreen(
                modifier = Modifier.padding(contentPadding),
                title = "Notifications"
            )
        }
    }
}

@Composable
private fun HubTopAppBar(
    destination: AppDestination,
    onNavigateHome: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                text = destination.appBarTitle,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        },
        navigationIcon = if (destination != AppDestination.Home) {
            {
                IconButton(onClick = onNavigateHome) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            }
        } else {
            null
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = HubPalette.TopBar,
            titleContentColor = HubPalette.OnBackground,
            navigationIconContentColor = HubPalette.OnBackground
        )
    )
}

@Composable
private fun SettingsDestination(
    modifier: Modifier = Modifier,
    state: ApplicationViewModel.State?,
    onScan: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: (String) -> Unit,
) {
    val connectedGlasses = state?.connectedGlasses

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(HubPalette.Background)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (connectedGlasses != null) {
            GlassesScreen(
                glasses = connectedGlasses,
                disconnect = { onDisconnect(connectedGlasses.id) }
            )
        } else {
            ScannerScreen(
                scanning = state?.scanning == true,
                error = state?.error == true,
                nearbyGlasses = state?.nearbyGlasses,
                scan = onScan,
                connect = onConnect
            )
        }

        SettingsScreen()
    }
}

@Composable
private fun SubtitlesDestination(
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var launchError by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            val intent = Intent().setClassName(
                context,
                "io.texne.g1.subtitles.MainActivity"
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            launchError = "Subtitles module not installed on this device."
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(HubPalette.Background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = launchError ?: "Launching Subtitles…",
                color = HubPalette.OnBackground,
                style = MaterialTheme.typography.titleMedium
            )
            if (launchError == null) {
                Text(
                    text = "You can return here after using subtitles.",
                    color = HubPalette.OnBackground.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun ComingSoonScreen(
    modifier: Modifier = Modifier,
    title: String,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(HubPalette.Background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$title coming soon",
                color = HubPalette.OnBackground,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "We're polishing the ${title.lowercase()} experience for a future release.",
                color = HubPalette.OnBackground.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

sealed class AppDestination(val id: String, val appBarTitle: String) {
    data object Home : AppDestination("home", "G1 Hub")
    data object Assistant : AppDestination("assistant", "Assistant")
    data object Settings : AppDestination("settings", "Settings")
    data object Subtitles : AppDestination("subtitles", "Subtitles")
    data object Todo : AppDestination("todo", "Todo")
    data object Navigation : AppDestination("navigation", "Navigation")
    data object EReader : AppDestination("ereader", "E-Reader")
    data object Notifications : AppDestination("notifications", "Notifications")

    companion object {
        fun saver(): Saver<AppDestination, String> = Saver(
            save = { it.id },
            restore = { value ->
                when (value) {
                    Assistant.id -> Assistant
                    Settings.id -> Settings
                    Subtitles.id -> Subtitles
                    Todo.id -> Todo
                    Navigation.id -> Navigation
                    EReader.id -> EReader
                    Notifications.id -> Notifications
                    else -> Home
                }
            }
        )
    }
}
