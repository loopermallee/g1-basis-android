package io.texne.g1.hub.ui.debug

import android.widget.Toast
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun DebugPanelScreen(
    modifier: Modifier = Modifier,
    viewModel: DebugViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    val context = LocalContext.current

    LaunchedEffect(state.message) {
        state.message?.let { message ->
            val duration = if (message.isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            Toast.makeText(context, message.text, duration).show()
            viewModel.consumeMessage()
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
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        state.lastUpdatedLabel?.let { label ->
            Text(
                text = "Last updated: $label",
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (state.isRefreshing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = viewModel::refresh,
                enabled = !state.isRefreshing
            ) {
                Text("Refresh")
            }
            OutlinedButton(onClick = viewModel::toggleAutoRefresh) {
                val label = if (state.autoRefreshEnabled) {
                    "Disable auto refresh"
                } else {
                    "Enable auto refresh"
                }
                Text(label)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = viewModel::copyToClipboard) {
                Text("Copy")
            }
            OutlinedButton(onClick = viewModel::exportToFile) {
                Text("Export")
            }
            OutlinedButton(onClick = viewModel::share) {
                Text("Share")
            }
        }
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            tonalElevation = 2.dp
        ) {
            SelectionContainer {
                Box(
                    modifier = Modifier
                        .verticalScroll(scrollState)
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    val text = state.formattedText.ifBlank { "Debug information will appear after refresh." }
                    Text(text = text, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}
