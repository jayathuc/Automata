package com.jayathu.automata.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jayathu.automata.data.model.DecisionMode
import com.jayathu.automata.data.model.TaskConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskConfigScreen(
    existingConfig: TaskConfig?,
    pickedPickup: String? = null,
    pickedDestination: String? = null,
    onPickOnMap: (fieldKey: String) -> Unit = {},
    onSave: (TaskConfig) -> Unit,
    onDelete: (() -> Unit)?,
    onBack: () -> Unit
) {
    // Compute effective initial values — picked value wins over existing config.
    // Using the effective value as a rememberSaveable input key forces reinitialization
    // when a map-picked value arrives, instead of restoring stale saved state.
    // Picked values are NOT consumed; they're cleaned up when the back stack entry pops.
    val effectivePickup = pickedPickup ?: existingConfig?.pickupAddress ?: ""
    val effectiveDestination = pickedDestination ?: existingConfig?.destinationAddress ?: ""

    var name by rememberSaveable { mutableStateOf(existingConfig?.name ?: "") }
    var pickup by rememberSaveable(effectivePickup) { mutableStateOf(effectivePickup) }
    var destination by rememberSaveable(effectiveDestination) { mutableStateOf(effectiveDestination) }
    var rideType by rememberSaveable { mutableStateOf(existingConfig?.rideType ?: "Bike") }
    var enablePickMe by rememberSaveable { mutableStateOf(existingConfig?.enablePickMe ?: true) }
    var enableUber by rememberSaveable { mutableStateOf(existingConfig?.enableUber ?: true) }
    var decisionMode by rememberSaveable { mutableStateOf(existingConfig?.decisionMode ?: DecisionMode.CHEAPEST) }
    val isEditing = existingConfig != null
    val isValid = name.isNotBlank() && destination.isNotBlank() && (enablePickMe || enableUber)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditing) "Edit Task" else "New Task") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (isEditing && onDelete != null) {
                        IconButton(onClick = onDelete) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Task Name") },
                placeholder = { Text("e.g., Morning Commute") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = pickup,
                    onValueChange = { pickup = it },
                    label = { Text("Pickup Address") },
                    placeholder = { Text("Leave empty for current location") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                IconButton(onClick = { onPickOnMap("pickup") }) {
                    Icon(Icons.Default.Map, contentDescription = "Pick on map")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = destination,
                    onValueChange = { destination = it },
                    label = { Text("Destination Address") },
                    placeholder = { Text("e.g., 123 Main Street") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                IconButton(onClick = { onPickOnMap("destination") }) {
                    Icon(Icons.Default.Map, contentDescription = "Pick on map")
                }
            }

            // Ride type
            Text("Ride Type", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf("Bike", "Tuk", "Car").forEachIndexed { index, type ->
                    SegmentedButton(
                        selected = rideType == type,
                        onClick = { rideType = type },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 3)
                    ) {
                        Text(type)
                    }
                }
            }

            // App toggles
            Text("Apps to Compare", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("PickMe", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = enablePickMe, onCheckedChange = { enablePickMe = it })
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Uber", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = enableUber, onCheckedChange = { enableUber = it })
            }

            // Decision mode
            Text("Decision Mode", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf(DecisionMode.CHEAPEST, DecisionMode.FASTEST).forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = decisionMode == mode,
                        onClick = { decisionMode = mode },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 2)
                    ) {
                        Text(if (mode == DecisionMode.CHEAPEST) "Cheapest" else "Fastest")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    onSave(
                        TaskConfig(
                            id = existingConfig?.id ?: 0,
                            name = name.trim(),
                            pickupAddress = pickup.trim(),
                            destinationAddress = destination.trim(),
                            rideType = rideType,
                            enablePickMe = enablePickMe,
                            enableUber = enableUber,
                            decisionMode = decisionMode
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = isValid
            ) {
                Text(if (isEditing) "Update Task" else "Create Task")
            }
        }
    }
}
