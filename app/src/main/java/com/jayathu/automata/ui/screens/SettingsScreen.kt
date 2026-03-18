package com.jayathu.automata.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jayathu.automata.data.model.DecisionMode
import com.jayathu.automata.data.model.SavedLocation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    savedLocations: List<SavedLocation>,
    autoEnableLocation: Boolean,
    debugMode: Boolean,
    autoBypassSomeoneElse: Boolean,
    overlayDurationSeconds: Int,
    showComparisonOverlay: Boolean,
    autoCloseApps: Boolean,
    defaultDecisionMode: DecisionMode,
    notificationSound: Boolean,
    onAutoEnableLocationChange: (Boolean) -> Unit,
    onDebugModeChange: (Boolean) -> Unit,
    onAutoBypassSomeoneElseChange: (Boolean) -> Unit,
    onOverlayDurationChange: (Int) -> Unit,
    onShowComparisonOverlayChange: (Boolean) -> Unit,
    onAutoCloseAppsChange: (Boolean) -> Unit,
    onDefaultDecisionModeChange: (DecisionMode) -> Unit,
    onNotificationSoundChange: (Boolean) -> Unit,
    onAddLocation: (SavedLocation) -> Unit,
    onDeleteLocation: (SavedLocation) -> Unit,
    onBack: () -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var showOverlayDurationDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Location")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // --- Automation section ---
            item {
                SectionHeader("Automation")
            }
            item {
                SettingsToggle(
                    title = "Auto-enable location",
                    description = "Automatically turn on location when starting a task",
                    checked = autoEnableLocation,
                    onCheckedChange = onAutoEnableLocationChange
                )
            }
            item {
                SettingsToggle(
                    title = "Auto-bypass \"someone else\" prompt",
                    description = "Automatically tap \"No, it's for me\" when Uber asks if the trip is for someone else",
                    checked = autoBypassSomeoneElse,
                    onCheckedChange = onAutoBypassSomeoneElseChange
                )
            }
            item {
                SettingsToggle(
                    title = "Auto-close apps after booking",
                    description = "Force-close PickMe and Uber after the ride is booked",
                    checked = autoCloseApps,
                    onCheckedChange = onAutoCloseAppsChange
                )
            }
            item {
                DefaultDecisionModeSelector(
                    currentMode = defaultDecisionMode,
                    onModeChange = onDefaultDecisionModeChange
                )
            }

            item { SettingsDivider() }

            // --- Display section ---
            item {
                SectionHeader("Display")
            }
            item {
                SettingsToggle(
                    title = "Show comparison overlay",
                    description = "Show a floating popup with price comparison results",
                    checked = showComparisonOverlay,
                    onCheckedChange = onShowComparisonOverlayChange
                )
            }
            item {
                SettingsClickable(
                    title = "Overlay display duration",
                    description = "${overlayDurationSeconds} seconds",
                    enabled = showComparisonOverlay,
                    onClick = { showOverlayDurationDialog = true }
                )
            }

            item { SettingsDivider() }

            // --- Notifications section ---
            item {
                SectionHeader("Notifications")
            }
            item {
                SettingsToggle(
                    title = "Notification sound",
                    description = "Play a sound when comparison results are ready",
                    checked = notificationSound,
                    onCheckedChange = onNotificationSoundChange
                )
            }

            item { SettingsDivider() }

            // --- Saved Locations section ---
            item {
                SectionHeader("Saved Locations")
            }
            if (savedLocations.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "No saved locations",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(savedLocations, key = { it.id }) { location ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp)
                            ) {
                                Text(
                                    text = location.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = location.address,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { onDeleteLocation(location) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            item { SettingsDivider() }

            // --- Developer section ---
            item {
                SectionHeader("Developer")
            }
            item {
                SettingsToggle(
                    title = "Debug mode",
                    description = "Show UI inspector and other developer tools",
                    checked = debugMode,
                    onCheckedChange = onDebugModeChange
                )
            }

            // Bottom spacing for FAB
            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }

    if (showAddDialog) {
        AddLocationDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, address ->
                onAddLocation(SavedLocation(name = name, address = address))
                showAddDialog = false
            }
        )
    }

    if (showOverlayDurationDialog) {
        OverlayDurationDialog(
            currentSeconds = overlayDurationSeconds,
            onDismiss = { showOverlayDurationDialog = false },
            onSelect = { seconds ->
                onOverlayDurationChange(seconds)
                showOverlayDurationDialog = false
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun SettingsToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SettingsClickable(
    title: String,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun DefaultDecisionModeSelector(
    currentMode: DecisionMode,
    onModeChange: (DecisionMode) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Text("Default decision mode", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Pre-selected mode when creating new tasks",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        DecisionMode.entries.forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onModeChange(mode) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = currentMode == mode,
                    onClick = { onModeChange(mode) }
                )
                Text(
                    text = when (mode) {
                        DecisionMode.CHEAPEST -> "Cheapest"
                        DecisionMode.FASTEST -> "Fastest"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun OverlayDurationDialog(
    currentSeconds: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    val options = listOf(5, 8, 12, 15)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Overlay Duration") },
        text = {
            Column {
                options.forEach { seconds ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(seconds) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentSeconds == seconds,
                            onClick = { onSelect(seconds) }
                        )
                        Text(
                            text = "$seconds seconds",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun AddLocationDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Location") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("e.g., Home") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Address") },
                    placeholder = { Text("e.g., 123 Main Street") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(name.trim(), address.trim()) },
                enabled = name.isNotBlank() && address.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
