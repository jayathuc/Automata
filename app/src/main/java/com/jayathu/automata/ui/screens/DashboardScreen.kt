package com.jayathu.automata.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jayathu.automata.data.model.TaskConfig
import com.jayathu.automata.engine.AutomationResult
import com.jayathu.automata.engine.ErrorMapper
import com.jayathu.automata.ui.AutomationUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    taskConfigs: List<TaskConfig>,
    automationState: AutomationUiState,
    dumpCountdown: Int,
    debugMode: Boolean = false,
    showRunWarning: Boolean = true,
    highlightTaskId: Long? = null,
    onHighlightConsumed: () -> Unit = {},
    onDismissRunWarning: () -> Unit = {},
    onAddTask: () -> Unit,
    onEditTask: (Long) -> Unit,
    onGoTask: (TaskConfig) -> Unit,
    onAbort: () -> Unit,
    onClearResult: () -> Unit,
    onEnableAccessibility: () -> Unit,
    onDumpUi: () -> Unit,
    onSettingsClick: () -> Unit
) {
    var pendingConfig by remember { mutableStateOf<TaskConfig?>(null) }
    var dontShowAgain by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    var highlightedId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(highlightTaskId) {
        if (highlightTaskId != null) {
            highlightedId = highlightTaskId
            listState.animateScrollToItem(0)
            snackbarHostState.showSnackbar("Task created")
            kotlinx.coroutines.delay(5000)
            highlightedId = null
            onHighlightConsumed()
        }
    }

    if (pendingConfig != null) {
        AlertDialog(
            onDismissRequest = { pendingConfig = null },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Heads up") },
            text = {
                Column {
                    Text(
                        "Please don't touch the screen while the script is running. If you need to stop it, use the floating stop button that appears on screen.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = dontShowAgain,
                            onCheckedChange = { dontShowAgain = it }
                        )
                        Text(
                            "Don't show this again",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val config = pendingConfig
                    pendingConfig = null
                    if (dontShowAgain) onDismissRunWarning()
                    if (config != null) onGoTask(config)
                }) {
                    Text("Start")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfig = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Automata") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    if (debugMode) {
                        IconButton(onClick = onDumpUi) {
                            Icon(Icons.Default.Visibility, contentDescription = "Dump UI Tree")
                        }
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (!automationState.isRunning) {
                FloatingActionButton(onClick = onAddTask) {
                    Icon(Icons.Default.Add, contentDescription = "Add Task")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Accessibility service banner
            AnimatedVisibility(visible = !automationState.accessibilityEnabled) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Accessibility service disabled",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                "Required for automation to work",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Button(
                            onClick = onEnableAccessibility,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Enable")
                        }
                    }
                }
            }

            // UI dump countdown banner
            AnimatedVisibility(visible = dumpCountdown >= 0) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Visibility,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = if (dumpCountdown > 0)
                                "Dumping UI in ${dumpCountdown}s — switch to the target app now!"
                            else
                                "Dumping UI tree... Check Logcat with tag 'UiInspector'",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }

            // Automation status banner
            AnimatedVisibility(visible = automationState.isRunning) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Automation Running",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Text(
                                automationState.currentStep,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        IconButton(onClick = onAbort) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = "Stop",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // Result banner
            AnimatedVisibility(visible = automationState.result != null && !automationState.isRunning) {
                automationState.result?.let { result ->
                    ResultBanner(result = result, onDismiss = onClearResult)
                }
            }

            // Task list
            if (taskConfigs.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.DirectionsBike,
                        contentDescription = null,
                        modifier = Modifier.height(64.dp).width(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No ride tasks yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Tap + to create your first automation",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(taskConfigs, key = { it.id }) { config ->
                        TaskCard(
                            config = config,
                            isRunning = automationState.isRunning,
                            highlighted = config.id == highlightedId,
                            onEdit = { onEditTask(config.id) },
                            onGo = {
                                if (showRunWarning) {
                                    pendingConfig = config
                                } else {
                                    onGoTask(config)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultBanner(result: AutomationResult, onDismiss: () -> Unit) {
    val info = when (result) {
        is AutomationResult.Success -> {
            val pickMe = result.data["pickme_price"]
            val uber = result.data["uber_price"]
            val winner = result.data["winner"]
            val msg = buildString {
                pickMe?.let { append("PickMe: Rs $it") }
                if (pickMe != null && uber != null) append(" | ")
                uber?.let { append("Uber: Rs $it") }
                winner?.let { append("\nWinner: $it") }
                if (isEmpty()) append("Completed successfully")
            }
            ResultInfo(
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.onPrimaryContainer,
                if (winner != null) "Best Ride Found" else "Prices Found",
                msg
            )
        }
        is AutomationResult.Failed -> {
            val friendly = ErrorMapper.map(result.stepName, result.reason)
            ResultInfo(
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
                friendly.title,
                if (friendly.suggestion.isNotBlank())
                    "${friendly.message}\n${friendly.suggestion}"
                else
                    friendly.message
            )
        }
        is AutomationResult.Aborted -> ResultInfo(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "Automation Aborted",
            "Stopped by user"
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = info.containerColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(info.title, style = MaterialTheme.typography.labelLarge, color = info.contentColor)
                Text(info.message, style = MaterialTheme.typography.bodySmall, color = info.contentColor)
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = info.contentColor)
            }
        }
    }
}

private data class ResultInfo(
    val containerColor: androidx.compose.ui.graphics.Color,
    val contentColor: androidx.compose.ui.graphics.Color,
    val title: String,
    val message: String
)

@Composable
private fun TaskCard(
    config: TaskConfig,
    isRunning: Boolean,
    highlighted: Boolean = false,
    onEdit: () -> Unit,
    onGo: () -> Unit
) {
    val borderWidth by animateDpAsState(
        targetValue = if (highlighted) 2.dp else 0.dp,
        animationSpec = tween(durationMillis = if (highlighted) 300 else 500),
        label = "cardBorder"
    )
    val borderColor = MaterialTheme.colorScheme.primary

    Card(
        onClick = onEdit,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (borderWidth > 0.dp)
                    Modifier.border(borderWidth, borderColor, RoundedCornerShape(12.dp))
                else Modifier
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = config.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = config.destinationAddress,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row {
                    if (config.enablePickMe) {
                        Text(
                            text = "PickMe",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (config.enablePickMe && config.enableUber) {
                        Text(
                            text = " + ",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (config.enableUber) {
                        Text(
                            text = "Uber",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            FloatingActionButton(
                onClick = onGo,
                containerColor = if (isRunning)
                    MaterialTheme.colorScheme.surfaceVariant
                else
                    MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Go",
                    tint = if (isRunning)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else
                        MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}
