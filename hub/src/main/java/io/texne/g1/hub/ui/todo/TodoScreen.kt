package io.texne.g1.hub.ui.todo

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.texne.g1.hub.todo.TodoItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoScreen(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    viewModel: TodoViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val activeTasks by viewModel.activeTasks.collectAsStateWithLifecycle()
    val archivedTasks by viewModel.archivedTasks.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.errorMessage) {
        val message = uiState.errorMessage
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    uiState.expandedTask?.let { expanded ->
        TaskDetailDialog(item = expanded, onDismiss = viewModel::clearExpandedTask)
    }

    val pullToRefreshState = rememberPullToRefreshState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("HUD Todo") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh todo list")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        PullToRefreshBox(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            isRefreshing = uiState.isRefreshing,
            onRefresh = viewModel::refresh,
            state = pullToRefreshState
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                TodoContent(
                    activeTasks = activeTasks,
                    archivedTasks = archivedTasks,
                    isLoading = uiState.isLoading,
                    onToggle = viewModel::toggleTask,
                    onArchive = viewModel::archiveTask,
                    onRestore = viewModel::restoreTask,
                    onExpand = viewModel::expandTask,
                    onAddTask = viewModel::addTask
                )

                if (uiState.isLoading && activeTasks.isEmpty() && archivedTasks.isEmpty()) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }
}

@Composable
private fun TodoContent(
    activeTasks: List<TodoItem>,
    archivedTasks: List<TodoItem>,
    isLoading: Boolean,
    onToggle: (String) -> Unit,
    onArchive: (String) -> Unit,
    onRestore: (String) -> Unit,
    onExpand: (String) -> Unit,
    onAddTask: (String, String) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            AddTaskCard(onAddTask = onAddTask)
        }

        if (activeTasks.isNotEmpty()) {
            item { SectionHeader(title = "Active tasks") }
            items(activeTasks, key = { it.id }) { item ->
                ActiveTaskRow(
                    item = item,
                    onToggle = onToggle,
                    onArchive = onArchive,
                    onExpand = onExpand
                )
            }
        } else if (!isLoading) {
            item {
                EmptyStateCard(
                    title = "No active tasks yet",
                    description = "Add a task above to start keeping track of HUD notes."
                )
            }
        }

        if (archivedTasks.isNotEmpty()) {
            item { SectionHeader(title = "Archived tasks") }
            items(archivedTasks, key = { it.id }) { item ->
                ArchivedTaskRow(
                    item = item,
                    onRestore = onRestore,
                    onExpand = onExpand
                )
            }
        }
    }
}

@Composable
private fun AddTaskCard(onAddTask: (String, String) -> Unit) {
    var shortText by rememberSaveable { mutableStateOf("") }
    var fullText by rememberSaveable { mutableStateOf("") }
    var showDetails by rememberSaveable { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Add a task",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = shortText,
                onValueChange = { shortText = it },
                label = { Text("Short HUD text") },
                singleLine = true,
                keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (shortText.isNotBlank()) {
                        val text = shortText.trim()
                        val details = if (showDetails) fullText.trim().ifBlank { text } else text
                        onAddTask(text, details)
                        shortText = ""
                        fullText = ""
                        showDetails = false
                    }
                })
            )

            AssistChip(
                onClick = { showDetails = !showDetails },
                label = { Text(if (showDetails) "Hide details" else "Add full description") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null
                    )
                }
            )

            AnimatedVisibility(visible = showDetails) {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .verticalScroll(rememberScrollState()),
                    value = fullText,
                    onValueChange = { fullText = it },
                    label = { Text("Detailed notes") }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                val canSave = shortText.isNotBlank()
                TextButton(
                    onClick = {
                        val text = shortText.trim()
                        if (text.isNotEmpty()) {
                            val details = if (showDetails) fullText.trim().ifBlank { text } else text
                            onAddTask(text, details)
                            shortText = ""
                            fullText = ""
                            showDetails = false
                        }
                    },
                    enabled = canSave
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add")
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun ActiveTaskRow(
    item: TodoItem,
    onToggle: (String) -> Unit,
    onArchive: (String) -> Unit,
    onExpand: (String) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Checkbox(
                checked = item.isDone,
                onCheckedChange = { onToggle(item.id) }
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.shortText,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                if (item.isDone) {
                    Text(
                        text = "Marked complete",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(imageVector = Icons.Default.MoreVert, contentDescription = "Task options")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Archive") },
                        leadingIcon = {
                            Icon(imageVector = Icons.Default.Archive, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onArchive(item.id)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("View details") },
                        leadingIcon = {
                            Icon(imageVector = Icons.Default.Description, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onExpand(item.id)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ArchivedTaskRow(
    item: TodoItem,
    onRestore: (String) -> Unit,
    onExpand: (String) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.shortText,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Archived",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(imageVector = Icons.Default.MoreVert, contentDescription = "Archived task options")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Restore") },
                        leadingIcon = {
                            Icon(imageVector = Icons.Default.Unarchive, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onRestore(item.id)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("View details") },
                        leadingIcon = {
                            Icon(imageVector = Icons.Default.Description, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onExpand(item.id)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyStateCard(title: String, description: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Text(text = description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TaskDetailDialog(item: TodoItem, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        title = { Text(text = item.shortText) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(text = item.fullText, style = MaterialTheme.typography.bodyMedium)
                if (item.archivedAt != null) {
                    Text(
                        text = "Archived task",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    )
}
