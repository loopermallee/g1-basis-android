package io.texne.g1.hub.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Send
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.texne.g1.hub.ai.ChatPersona
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.texne.g1.hub.todo.TodoDisplayMode
import io.texne.g1.hub.todo.TodoItem
import io.texne.g1.hub.todo.TodoViewModel
import kotlinx.coroutines.delay

@Composable
fun ChatScreen(
    connectedGlassesName: String?,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
    chatViewModel: ChatViewModel = hiltViewModel(),
    todoViewModel: TodoViewModel = hiltViewModel()
) {
    val chatState by chatViewModel.state.collectAsStateWithLifecycle()
    val todoState by todoViewModel.state.collectAsStateWithLifecycle()

    ChatContent(
        chatState = chatState,
        todoState = todoState,
        connectedGlassesName = connectedGlassesName,
        onPersonaSelected = chatViewModel::onPersonaSelected,
        onSendPrompt = chatViewModel::sendPrompt,
        onNavigateToSettings = onNavigateToSettings,
        onDismissError = chatViewModel::clearError,
        onHudStatusConsumed = chatViewModel::clearHudStatus,
        onTodoToggle = todoViewModel::onTaskToggle,
        onTodoMoveUp = todoViewModel::onMoveTaskUp,
        onTodoMoveDown = todoViewModel::onMoveTaskDown,
        onTodoModeSelected = todoViewModel::onDisplayModeSelected,
        onTodoShowFullList = todoViewModel::onShowFullList,
        onTodoShowSummary = todoViewModel::onShowSummary,
        onTodoNextPage = todoViewModel::onSwipeNextPage,
        onTodoPreviousPage = todoViewModel::onSwipePreviousPage
    )
}

@Composable
private fun ChatContent(
    chatState: ChatViewModel.State,
    todoState: TodoViewModel.State,
    connectedGlassesName: String?,
    onPersonaSelected: (ChatPersona) -> Unit,
    onSendPrompt: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onDismissError: () -> Unit,
    onHudStatusConsumed: () -> Unit,
    onTodoToggle: (Long) -> Unit,
    onTodoMoveUp: (Long) -> Unit,
    onTodoMoveDown: (Long) -> Unit,
    onTodoModeSelected: (TodoDisplayMode) -> Unit,
    onTodoShowFullList: () -> Unit,
    onTodoShowSummary: () -> Unit,
    onTodoNextPage: () -> Unit,
    onTodoPreviousPage: () -> Unit,
    modifier: Modifier = Modifier
) {
    var prompt by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(chatState.messages.size) {
        if (chatState.messages.isNotEmpty()) {
            listState.animateScrollToItem(chatState.messages.lastIndex)
        }
    }

    LaunchedEffect(chatState.hudStatus) {
        if (chatState.hudStatus is ChatViewModel.HudStatus.Displayed) {
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

        if (!chatState.apiKeyAvailable) {
            ApiKeyWarningCard(onNavigateToSettings = onNavigateToSettings)
        }

        PersonaSelector(
            personas = chatState.availablePersonas,
            selected = chatState.selectedPersona,
            onPersonaSelected = onPersonaSelected
        )

        if (chatState.errorMessage != null) {
            ErrorCard(message = chatState.errorMessage, onDismiss = onDismissError)
        }

        when (val hudStatus = chatState.hudStatus) {
            is ChatViewModel.HudStatus.DisplayFailed -> {
                HudStatusCard(
                    text = "Unable to display response on the HUD. Check the connection and try again.",
                    highlight = true,
                    onDismiss = onHudStatusConsumed
                )
            }
            is ChatViewModel.HudStatus.Displayed -> {
                val message = when {
                    hudStatus.pageCount > 1 && hudStatus.truncated ->
                        "Response paginated across ${hudStatus.pageCount} HUD pages (trimmed to fit width)."
                    hudStatus.pageCount > 1 ->
                        if (hudStatus.pageCount == 2) {
                            "Response paginated across 2 HUD pages."
                        } else {
                            "Response paginated across ${hudStatus.pageCount} HUD pages."
                        }
                    hudStatus.truncated ->
                        "Response shown on the HUD (trimmed to fit)."
                    else ->
                        "Response sent to the HUD."
                }
                HudStatusCard(text = message, onDismiss = onHudStatusConsumed)
            }
            ChatViewModel.HudStatus.Idle -> Unit
        }

        TodoHudPanel(
            state = todoState,
            onToggle = onTodoToggle,
            onMoveUp = onTodoMoveUp,
            onMoveDown = onTodoMoveDown,
            onModeSelected = onTodoModeSelected,
            onShowFullList = onTodoShowFullList,
            onShowSummary = onTodoShowSummary,
            onNextPage = onTodoNextPage,
            onPreviousPage = onTodoPreviousPage
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (chatState.isSending) {
                LoadingIndicator()
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (chatState.messages.isEmpty()) {
                    item {
                        Text(
                            text = "Ask anything to get started.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(chatState.messages, key = { it.id }) { message ->
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
                    if (chatState.selectedPersona.description.isNotEmpty()) {
                        Text(
                            text = chatState.selectedPersona.description,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            )

            val sendEnabled = prompt.isNotBlank() && chatState.apiKeyAvailable && !chatState.isSending
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
private fun TodoHudPanel(
    state: TodoViewModel.State,
    onToggle: (Long) -> Unit,
    onMoveUp: (Long) -> Unit,
    onMoveDown: (Long) -> Unit,
    onModeSelected: (TodoDisplayMode) -> Unit,
    onShowFullList: () -> Unit,
    onShowSummary: () -> Unit,
    onNextPage: () -> Unit,
    onPreviousPage: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Todo HUD",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = state.isSummaryMode,
                    onClick = onShowSummary,
                    label = { Text("Summary") },
                    leadingIcon = if (state.isSummaryMode) {
                        { Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )

                FilterChip(
                    selected = state.isFullMode,
                    onClick = { onModeSelected(TodoDisplayMode.FULL) },
                    label = { Text("Full text") },
                    leadingIcon = if (state.isFullMode) {
                        { Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )

                if (state.showingSingleTask) {
                    TextButton(onClick = onShowFullList) {
                        Text("Show all tasks")
                    }
                }
            }

            if (state.showingSingleTask) {
                val taskNumber = state.tasks.indexOfFirst { it.id == state.expandedTaskId } + 1
                if (taskNumber > 0) {
                    Text(
                        text = "Showing full text for task #$taskNumber",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            val pageCount = state.pageCount
            val pageIndicator = if (pageCount == 0) {
                "Page --"
            } else {
                "Page ${state.currentPageIndex + 1} / $pageCount"
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onPreviousPage,
                    enabled = pageCount > 0 && state.currentPageIndex > 0
                ) {
                    Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Previous HUD page")
                }
                Text(text = pageIndicator, style = MaterialTheme.typography.bodyMedium)
                IconButton(
                    onClick = onNextPage,
                    enabled = pageCount > 0 && state.currentPageIndex < pageCount - 1
                ) {
                    Icon(imageVector = Icons.Default.ArrowForward, contentDescription = "Next HUD page")
                }
            }

            if (state.tasks.isEmpty()) {
                Text(
                    text = "No todo items yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.tasks.forEachIndexed { index, item ->
                        val highlighted = state.summaryHighlightRange?.contains(index) == true
                        TodoRow(
                            index = index,
                            item = item,
                            highlighted = highlighted,
                            canMoveUp = index > 0,
                            canMoveDown = index < state.tasks.lastIndex,
                            onToggle = onToggle,
                            onMoveUp = onMoveUp,
                            onMoveDown = onMoveDown
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TodoRow(
    index: Int,
    item: TodoItem,
    highlighted: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: (Long) -> Unit,
    onMoveUp: (Long) -> Unit,
    onMoveDown: (Long) -> Unit
) {
    val backgroundColor = if (highlighted) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(backgroundColor)
            .clickable { onToggle(item.id) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "${index + 1}.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = if (item.isCompleted) "[✔]" else "[ ]",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = item.summary,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.width(4.dp))
        IconButton(
            onClick = { onMoveUp(item.id) },
            enabled = canMoveUp
        ) {
            Icon(imageVector = Icons.Default.ArrowUpward, contentDescription = "Move task up")
        }
        IconButton(
            onClick = { onMoveDown(item.id) },
            enabled = canMoveDown
        ) {
            Icon(imageVector = Icons.Default.ArrowDownward, contentDescription = "Move task down")
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
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
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
