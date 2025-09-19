package io.texne.g1.hub.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
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
import io.texne.g1.hub.todo.TodoHudFormatter
import io.texne.g1.hub.todo.TodoHudFormatter.DisplayMode
import io.texne.g1.hub.ui.todo.TodoViewModel
import kotlinx.coroutines.delay

@Composable
fun ChatScreen(
    connectedGlassesName: String?,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel(),
    todoViewModel: TodoViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val todoState by todoViewModel.state.collectAsStateWithLifecycle()

    ChatContent(
        state = state,
        todoState = todoState,
        connectedGlassesName = connectedGlassesName,
        onPersonaSelected = viewModel::onPersonaSelected,
        onSendPrompt = viewModel::sendPrompt,
        onNavigateToSettings = onNavigateToSettings,
        onDismissError = viewModel::clearError,
        onHudStatusConsumed = viewModel::clearHudStatus,
        onToggleTask = todoViewModel::toggleTask,
        onMoveTaskUp = todoViewModel::moveTaskUp,
        onMoveTaskDown = todoViewModel::moveTaskDown,
        onDisplayModeSelected = todoViewModel::setDisplayMode,
        onExpandTask = todoViewModel::expandTask,
        onCollapseExpanded = todoViewModel::collapseExpanded,
        onNextTodoPage = todoViewModel::goToNextPage,
        onPreviousTodoPage = todoViewModel::goToPreviousPage,
        onClearTodoHudError = todoViewModel::clearHudError
    )
}

@Composable
private fun ChatContent(
    state: ChatViewModel.State,
    todoState: TodoViewModel.State,
    connectedGlassesName: String?,
    onPersonaSelected: (ChatPersona) -> Unit,
    onSendPrompt: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onDismissError: () -> Unit,
    onHudStatusConsumed: () -> Unit,
    onToggleTask: (String) -> Unit,
    onMoveTaskUp: (String) -> Unit,
    onMoveTaskDown: (String) -> Unit,
    onDisplayModeSelected: (DisplayMode) -> Unit,
    onExpandTask: (Int) -> Unit,
    onCollapseExpanded: () -> Unit,
    onNextTodoPage: () -> Unit,
    onPreviousTodoPage: () -> Unit,
    onClearTodoHudError: () -> Unit,
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

        TodoHudPanel(
            state = todoState,
            onToggleTask = onToggleTask,
            onMoveTaskUp = onMoveTaskUp,
            onMoveTaskDown = onMoveTaskDown,
            onDisplayModeSelected = onDisplayModeSelected,
            onExpandTask = onExpandTask,
            onCollapseExpanded = onCollapseExpanded,
            onNextPage = onNextTodoPage,
            onPreviousPage = onPreviousTodoPage,
            onClearHudError = onClearTodoHudError
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
private fun TodoHudPanel(
    state: TodoViewModel.State,
    onToggleTask: (String) -> Unit,
    onMoveTaskUp: (String) -> Unit,
    onMoveTaskDown: (String) -> Unit,
    onDisplayModeSelected: (DisplayMode) -> Unit,
    onExpandTask: (Int) -> Unit,
    onCollapseExpanded: () -> Unit,
    onNextPage: () -> Unit,
    onPreviousPage: () -> Unit,
    onClearHudError: () -> Unit
) {
    val undoIndexMap = state.tasks
        .filter { !it.isDone }
        .mapIndexed { index, item -> item.id to index }
        .toMap()
    val lastUndoneIndex = undoIndexMap.values.maxOrNull() ?: -1

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Todo HUD",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DisplayModeChip(
                        label = "Summary",
                        selected = state.displayMode == DisplayMode.SUMMARY,
                        onClick = { onDisplayModeSelected(DisplayMode.SUMMARY) }
                    )
                    DisplayModeChip(
                        label = "Full text",
                        selected = state.displayMode == DisplayMode.FULL,
                        onClick = { onDisplayModeSelected(DisplayMode.FULL) }
                    )
                }
            }

            if (state.hudError) {
                TextButton(onClick = onClearHudError) {
                    Text("Unable to update HUD. Tap to dismiss.")
                }
            }

            if (state.tasks.isEmpty()) {
                Text(
                    text = "No active tasks.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val displayTasks = state.tasks
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(displayTasks, key = { _, item -> item.id }) { index, task ->
                        val undoneIndex = undoIndexMap[task.id]
                        TodoTaskRow(
                            index = index,
                            task = task,
                            displayMode = state.displayMode,
                            expanded = state.expandedTaskId == task.id,
                            canMoveUp = undoneIndex != null && undoneIndex > 0,
                            canMoveDown = undoneIndex != null && undoneIndex < lastUndoneIndex,
                            onToggleTask = onToggleTask,
                            onMoveTaskUp = onMoveTaskUp,
                            onMoveTaskDown = onMoveTaskDown,
                            onExpandTask = onExpandTask,
                            onCollapseExpanded = onCollapseExpanded
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "HUD Page ${state.pageIndex + 1} / ${state.pageCount}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onPreviousPage, enabled = state.pageIndex > 0) {
                        Text("Prev")
                    }
                    TextButton(onClick = onNextPage, enabled = state.pageIndex + 1 < state.pageCount) {
                        Text("Next")
                    }
                }
            }
        }
    }
}

@Composable
private fun DisplayModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}

@Composable
private fun TodoTaskRow(
    index: Int,
    task: io.texne.g1.hub.todo.TodoItem,
    displayMode: DisplayMode,
    expanded: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggleTask: (String) -> Unit,
    onMoveTaskUp: (String) -> Unit,
    onMoveTaskDown: (String) -> Unit,
    onExpandTask: (Int) -> Unit,
    onCollapseExpanded: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleTask(task.id) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val icon = if (task.isDone) TodoHudFormatter.HUD_CHECKED_ICON else TodoHudFormatter.HUD_UNCHECKED_ICON
        val preview = when (displayMode) {
            DisplayMode.SUMMARY -> task.shortText
            DisplayMode.FULL -> task.fullText
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${index + 1}. $icon $preview",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (expanded) {
                Text(
                    text = task.fullText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = { onToggleTask(task.id) }) {
            Icon(Icons.Filled.Check, contentDescription = "Toggle completion")
        }
        if (!task.isDone) {
            IconButton(onClick = { onMoveTaskUp(task.id) }, enabled = canMoveUp) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up")
            }
            IconButton(onClick = { onMoveTaskDown(task.id) }, enabled = canMoveDown) {
                Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down")
            }
        }
        if (expanded) {
            TextButton(onClick = onCollapseExpanded) {
                Text("Collapse")
            }
        } else {
            TextButton(onClick = { onExpandTask(index + 1) }) {
                Text("Expand")
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
