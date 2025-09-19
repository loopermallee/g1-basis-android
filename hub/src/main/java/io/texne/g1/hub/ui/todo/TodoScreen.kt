package io.texne.g1.hub.ui.todo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.texne.g1.hub.todo.TodoItem
import io.texne.g1.hub.ui.home.HubPalette

@Composable
fun TodoScreen(
    modifier: Modifier = Modifier,
    viewModel: TodoViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TodoContent(
        state = state,
        onAddTask = viewModel::addTask,
        onToggleTask = viewModel::toggleTask,
        onArchiveTask = viewModel::archiveTask,
        onRestoreTask = viewModel::restoreTask,
        onDismissMessage = viewModel::consumeMessage,
        modifier = modifier
    )
}

@Composable
private fun TodoContent(
    state: TodoState,
    onAddTask: (String) -> Unit,
    onToggleTask: (String) -> Unit,
    onArchiveTask: (String) -> Unit,
    onRestoreTask: (String) -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var newTaskText by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(HubPalette.Background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Todo",
            style = MaterialTheme.typography.headlineSmall,
            color = HubPalette.OnBackground,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Capture quick notes and action items for your day.",
            style = MaterialTheme.typography.bodyMedium,
            color = HubPalette.OnBackground.copy(alpha = 0.75f)
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = HubPalette.Surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = newTaskText,
                    onValueChange = { newTaskText = it },
                    label = { Text("New task") }
                )
                Button(
                    onClick = {
                        onAddTask(newTaskText)
                        newTaskText = ""
                    },
                    enabled = newTaskText.isNotBlank()
                ) {
                    Text("Add task")
                }
            }
        }

        state.errorMessage?.let { message ->
            Surface(
                color = HubPalette.Surface,
                tonalElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = message,
                        color = HubPalette.OnSurface,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismissMessage) {
                        Text("Dismiss")
                    }
                }
            }
        }

        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    SectionHeader(title = "Active tasks")
                }
                if (state.activeTasks.isEmpty()) {
                    item {
                        Text(
                            text = "You're all caught up!",
                            color = HubPalette.OnBackground.copy(alpha = 0.7f),
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                } else {
                    items(state.activeTasks, key = { it.id }) { task ->
                        ActiveTaskRow(
                            task = task,
                            onToggle = onToggleTask,
                            onArchive = onArchiveTask
                        )
                    }
                }

                if (state.archivedTasks.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        SectionHeader(title = "Archived")
                    }
                    items(state.archivedTasks, key = { it.id }) { task ->
                        ArchivedTaskRow(
                            task = task,
                            onRestore = onRestoreTask
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = HubPalette.OnBackground,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun ActiveTaskRow(
    task: TodoItem,
    onToggle: (String) -> Unit,
    onArchive: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = HubPalette.Surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = task.shortText,
                style = MaterialTheme.typography.titleMedium,
                color = HubPalette.OnSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onToggle(task.id) }) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = "Mark complete",
                        tint = HubPalette.Accent
                    )
                }
                IconButton(onClick = { onArchive(task.id) }) {
                    Icon(
                        imageVector = Icons.Outlined.Archive,
                        contentDescription = "Archive",
                        tint = HubPalette.OnSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ArchivedTaskRow(
    task: TodoItem,
    onRestore: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = HubPalette.Surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = task.shortText,
                style = MaterialTheme.typography.bodyLarge,
                color = HubPalette.OnSurface.copy(alpha = 0.8f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Divider(color = HubPalette.CardAccent)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onRestore(task.id) }) {
                    Icon(
                        imageVector = Icons.Outlined.Restore,
                        contentDescription = "Restore",
                        tint = HubPalette.Accent
                    )
                }
            }
        }
    }
}
