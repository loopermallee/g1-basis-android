package io.texne.g1.hub.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.texne.g1.hub.ai.ChatPersona
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

@Composable
fun ChatScreen(
    connectedGlassesName: String?,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ChatContent(
        state = state,
        connectedGlassesName = connectedGlassesName,
        onPersonaSelected = viewModel::onPersonaSelected,
        onSendPrompt = viewModel::sendPrompt,
        onNavigateToSettings = onNavigateToSettings,
        onDismissError = viewModel::clearError,
        onHudStatusConsumed = viewModel::clearHudStatus
    )
}

@Composable
private fun ChatContent(
    state: ChatViewModel.State,
    connectedGlassesName: String?,
    onPersonaSelected: (ChatPersona) -> Unit,
    onSendPrompt: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onDismissError: () -> Unit,
    onHudStatusConsumed: () -> Unit,
    modifier: Modifier = Modifier
) {
    var prompt by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    LaunchedEffect(state.hudStatus) {
        if (state.hudStatus is ChatViewModel.HudStatus.Displayed) {
            delay(3_000)
            onHudStatusConsumed()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "ChatGPT Assistant",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        ConnectionStatus(connectedGlassesName)

        if (!state.apiKeyAvailable) {
            ApiKeyWarningCard(onNavigateToSettings = onNavigateToSettings)
        }

        PersonaSelector(
            personas = state.availablePersonas,
            selected = state.selectedPersona,
            onPersonaSelected = onPersonaSelected
        )

        if (state.errorMessage != null) {
            ErrorCard(message = state.errorMessage, onDismiss = onDismissError)
        }

        when (val hudStatus = state.hudStatus) {
            is ChatViewModel.HudStatus.DisplayFailed -> {
                HudStatusCard(
                    text = "Unable to display response on the HUD. Check the connection and try again.",
                    highlight = true,
                    onDismiss = onHudStatusConsumed
                )
            }
            is ChatViewModel.HudStatus.Displayed -> {
                val message = if (hudStatus.truncated) {
                    "Response shown on the HUD (trimmed to fit)."
                } else {
                    "Response sent to the HUD."
                }
                HudStatusCard(text = message, onDismiss = onHudStatusConsumed)
            }
            ChatViewModel.HudStatus.Idle -> Unit
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (state.isSending) {
                LoadingIndicator()
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (state.messages.isEmpty()) {
                    item {
                        Text(
                            text = "Ask anything to get started.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(state.messages, key = { it.id }) { message ->
                        MessageBubble(message = message)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                modifier = Modifier.weight(1f),
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Ask the assistant") },
                maxLines = 3,
                supportingText = {
                    if (state.selectedPersona.description.isNotEmpty()) {
                        Text(
                            text = state.selectedPersona.description,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            )

            val sendEnabled = prompt.isNotBlank() && state.apiKeyAvailable && !state.isSending
            IconButton(
                onClick = {
                    if (sendEnabled) {
                        onSendPrompt(prompt.trim())
                        prompt = ""
                    }
                },
                enabled = sendEnabled
            ) {
                Icon(imageVector = Icons.Default.Send, contentDescription = "Send prompt")
            }
        }
    }
}

@Composable
private fun ConnectionStatus(connectedGlassesName: String?) {
    val text = connectedGlassesName?.let { "Connected to $it" }
        ?: "No glasses connected. Responses will not appear on the HUD."
    val color = if (connectedGlassesName != null) {
        MaterialTheme.colorScheme.secondary
    } else {
        MaterialTheme.colorScheme.error
    }
    Text(text = text, color = color, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun PersonaSelector(
    personas: List<ChatPersona>,
    selected: ChatPersona,
    onPersonaSelected: (ChatPersona) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        personas.forEach { persona ->
            val selectedPersona = persona.id == selected.id
            FilterChip(
                selected = selectedPersona,
                onClick = { onPersonaSelected(persona) },
                label = { Text(persona.displayName) },
                leadingIcon = if (selectedPersona) {
                    {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else null,
                colors = if (selectedPersona) {
                    FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        leadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                } else {
                    FilterChipDefaults.filterChipColors()
                }
            )
        }
    }
}

@Composable
private fun MessageBubble(message: ChatViewModel.UiMessage) {
    val isUser = message.role == ChatViewModel.UiMessage.Role.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = if (isUser) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surface
            }
        ) {
            Text(
                text = message.text,
                modifier = Modifier.padding(12.dp),
                color = if (isUser) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = "ChatGPT error", style = MaterialTheme.typography.titleSmall)
            Text(text = message, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    }
}

@Composable
private fun ApiKeyWarningCard(onNavigateToSettings: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Add your ChatGPT API key to use the assistant.")
            Button(onClick = onNavigateToSettings) {
                Text("Open settings")
            }
        }
    }
}

@Composable
private fun HudStatusCard(
    text: String,
    onDismiss: () -> Unit,
    highlight: Boolean = false
) {
    val colors = if (highlight) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    }
    Card(colors = colors) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = text, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        }
    }
}

@Composable
private fun LoadingIndicator() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.height(24.dp).width(24.dp))
    }
}
