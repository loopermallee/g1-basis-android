package io.texne.g1.hub.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.texne.g1.hub.assistant.AssistantActivationGesture
import io.texne.g1.hub.R

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var revealKey by rememberSaveable { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
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

        AssistantActivationCard(
            selectedGesture = state.activationGesture,
            onGestureSelected = viewModel::setActivationGesture
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
private fun AssistantActivationCard(
    selectedGesture: AssistantActivationGesture,
    onGestureSelected: (AssistantActivationGesture) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(id = R.string.settings_assistant_activation_title),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = stringResource(id = R.string.settings_assistant_activation_description),
                style = MaterialTheme.typography.bodySmall
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AssistantActivationGesture.entries.forEach { gesture ->
                    AssistantActivationOption(
                        gesture = gesture,
                        selected = gesture == selectedGesture,
                        onSelected = onGestureSelected
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantActivationOption(
    gesture: AssistantActivationGesture,
    selected: Boolean,
    onSelected: (AssistantActivationGesture) -> Unit
) {
    val label = stringResource(id = gesture.labelRes)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = { onSelected(gesture) },
                role = Role.RadioButton
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RadioButton(
            selected = selected,
            onClick = null
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
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
