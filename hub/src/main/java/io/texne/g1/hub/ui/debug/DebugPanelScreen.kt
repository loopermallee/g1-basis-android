package io.texne.g1.hub.ui.debug

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date

@Composable
fun DebugPanelScreen(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    viewModel: DebugViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            snackbarHostState.showSnackbar(event.text)
            if (event.isError) {
                Toast.makeText(context, event.text, Toast.LENGTH_LONG).show()
            }
        }
    }

    DebugPanelContent(
        state = state,
        onCopyDebugInfo = viewModel::copyDebugInfo,
        onExportDebugLog = viewModel::exportDebugLog,
        onShareDebugInfo = viewModel::shareDebugInfo,
        onAutoRefreshChanged = viewModel::setAutoRefreshEnabled,
        modifier = modifier
    )
}

@Composable
private fun DebugPanelContent(
    state: DebugViewModel.State,
    onCopyDebugInfo: () -> Unit,
    onExportDebugLog: () -> Unit,
    onShareDebugInfo: () -> Unit,
    onAutoRefreshChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val lastUpdatedText = remember(state.lastUpdatedMillis) {
        state.lastUpdatedMillis?.let { millis ->
            DateFormat.getDateTimeInstance().format(Date(millis))
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Debug Panel",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Inspect connection details and export logs for support.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        lastUpdatedText?.let {
            Text(
                text = "Last updated: ${'$'}it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AnimatedVisibility(
            visible = state.isRefreshing,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth()
            )
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(16.dp)
            ) {
                Text(
                    text = state.debugInfo.ifBlank { "No debug information available." },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onCopyDebugInfo,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.debugInfo.isNotEmpty()
            ) {
                Text("📋 Copy Debug Info")
            }

            FilledTonalButton(
                onClick = onExportDebugLog,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.debugInfo.isNotEmpty()
            ) {
                Text("⬇️ Export Debug Log")
            }

            if (state.isShareAvailable) {
                OutlinedButton(
                    onClick = onShareDebugInfo,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.debugInfo.isNotEmpty()
                ) {
                    Text("Share Debug Info")
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "Live Auto Refresh",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = if (state.isAutoRefreshEnabled) {
                        "Debug info updates automatically while enabled."
                    } else {
                        "Enable to keep the debug panel in sync with service changes."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = state.isAutoRefreshEnabled,
                onCheckedChange = onAutoRefreshChanged
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}
