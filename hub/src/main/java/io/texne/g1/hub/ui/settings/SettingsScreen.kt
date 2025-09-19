package io.texne.g1.hub.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.texne.g1.hub.settings.HudWidget
import io.texne.g1.hub.settings.HudWidgetType

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
    hudSettingsViewModel: HudSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hudState by hudSettingsViewModel.state.collectAsStateWithLifecycle()
    var revealKey by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "ChatGPT Integration",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Store your personal OpenAI API key locally on this device. The key is encrypted using Android's EncryptedSharedPreferences.",
            style = MaterialTheme.typography.bodyMedium
        )

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = state.inputKey,
            onValueChange = viewModel::onInputChanged,
            label = { Text("OpenAI API Key") },
            placeholder = { Text("sk-...") },
            visualTransformation = if (revealKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { revealKey = !revealKey }) {
                    val icon = if (revealKey) Icons.Default.VisibilityOff else Icons.Default.Visibility
                    Icon(imageVector = icon, contentDescription = if (revealKey) "Hide key" else "Show key")
                }
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(onClick = viewModel::saveKey, enabled = !state.isSaving) {
                Text("Save")
            }
            TextButton(onClick = viewModel::clearKey, enabled = !state.isSaving) {
                Text("Clear stored key")
            }
            if (state.isSaving) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }

        val keySummary = if (state.currentKey.isBlank()) {
            "No key saved yet."
        } else {
            val suffix = state.currentKey.takeLast(4)
            "Stored key detected (…$suffix)"
        }
        Text(keySummary, style = MaterialTheme.typography.bodySmall)

        state.message?.let { message ->
            StatusMessage(message = message, onDismiss = viewModel::consumeMessage)
        }

        HudWidgetSection(
            widgets = hudState.widgets,
            onToggle = hudSettingsViewModel::setEnabled,
            onMoveUp = hudSettingsViewModel::moveUp,
            onMoveDown = hudSettingsViewModel::moveDown
        )

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Usage",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "Your key is only used for ChatGPT calls made from this device. Remember that usage is billed to your OpenAI account.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Tip: switch personalities in the Assistant tab to tailor responses.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun StatusMessage(message: String, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(message, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        }
    }
}

@Composable
private fun HudWidgetSection(
    widgets: List<HudWidget>,
    onToggle: (HudWidgetType, Boolean) -> Unit,
    onMoveUp: (HudWidgetType) -> Unit,
    onMoveDown: (HudWidgetType) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "HUD Widgets",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Choose what appears on the glasses status bar and reorder the priority. The first enabled widget renders first.",
                style = MaterialTheme.typography.bodySmall
            )
            widgets.forEachIndexed { index, widget ->
                HudWidgetRow(
                    widget = widget,
                    isFirst = index == 0,
                    isLast = index == widgets.lastIndex,
                    onToggle = { enabled -> onToggle(widget.type, enabled) },
                    onMoveUp = { onMoveUp(widget.type) },
                    onMoveDown = { onMoveDown(widget.type) }
                )
            }
        }
    }
}

@Composable
private fun HudWidgetRow(
    widget: HudWidget,
    isFirst: Boolean,
    isLast: Boolean,
    onToggle: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(imageVector = iconFor(widget.type), contentDescription = null)
            Column {
                Text(
                    text = widget.type.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = widget.type.emoji,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = onMoveUp, enabled = !isFirst) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "Move up"
                    )
                }
                IconButton(onClick = onMoveDown, enabled = !isLast) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Move down"
                    )
                }
            }
            Switch(
                checked = widget.enabled,
                onCheckedChange = onToggle
            )
        }
    }
}

@Composable
private fun iconFor(type: HudWidgetType) = when (type) {
    HudWidgetType.CLOCK -> Icons.Default.AccessTime
    HudWidgetType.WEATHER -> Icons.Default.WbSunny
    HudWidgetType.NEWS -> Icons.Default.Article
    HudWidgetType.NOTIFICATIONS -> Icons.Default.Notifications
}
