package com.jayathu.automata.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.jayathu.automata.ui.MainViewModel
import com.jayathu.automata.ui.screens.DashboardScreen
import com.jayathu.automata.ui.screens.MapPickerScreen
import com.jayathu.automata.ui.screens.SettingsScreen
import com.jayathu.automata.ui.screens.TaskConfigScreen

object Routes {
    const val DASHBOARD = "dashboard"
    const val NEW_TASK = "task/new"
    const val EDIT_TASK = "task/edit/{taskId}"
    const val SETTINGS = "settings"
    const val MAP_PICKER = "map_picker/{fieldKey}"

    fun editTask(taskId: Long) = "task/edit/$taskId"
    fun mapPicker(fieldKey: String) = "map_picker/$fieldKey"
}

@Composable
fun AutomataNavGraph(
    navController: NavHostController,
    viewModel: MainViewModel
) {
    NavHost(navController = navController, startDestination = Routes.DASHBOARD) {
        composable(Routes.DASHBOARD) {
            val taskConfigs by viewModel.taskConfigs.collectAsState()
            val automationState by viewModel.automationState.collectAsState()
            val dumpCountdown by viewModel.dumpCountdown.collectAsState()
            val debugMode by viewModel.debugMode.collectAsState()
            val showRunWarning by viewModel.showRunWarning.collectAsState()
            DashboardScreen(
                taskConfigs = taskConfigs,
                automationState = automationState,
                dumpCountdown = dumpCountdown,
                debugMode = debugMode,
                showRunWarning = showRunWarning,
                onDismissRunWarning = { viewModel.setShowRunWarning(false) },
                onAddTask = { navController.navigate(Routes.NEW_TASK) },
                onEditTask = { id -> navController.navigate(Routes.editTask(id)) },
                onGoTask = { config -> viewModel.runAutomation(config) },
                onAbort = { viewModel.abortAutomation() },
                onClearResult = { viewModel.clearResult() },
                onEnableAccessibility = { viewModel.openAccessibilitySettings() },
                onDumpUi = { viewModel.dumpCurrentUi() },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) }
            )
        }

        composable(Routes.NEW_TASK) { backStackEntry ->
            // Observe map picker results
            val pickedPickup = backStackEntry.savedStateHandle.getStateFlow<String?>("picked_pickup", null)
                .collectAsState()
            val pickedDestination = backStackEntry.savedStateHandle.getStateFlow<String?>("picked_destination", null)
                .collectAsState()

            TaskConfigScreen(
                existingConfig = null,
                pickedPickup = pickedPickup.value,
                pickedDestination = pickedDestination.value,
                onPickOnMap = { fieldKey -> navController.navigate(Routes.mapPicker(fieldKey)) },
                onSave = { config ->
                    viewModel.saveTaskConfig(config)
                    navController.popBackStack()
                },
                onDelete = null,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.EDIT_TASK,
            arguments = listOf(navArgument("taskId") { type = NavType.LongType })
        ) { backStackEntry ->
            val taskId = backStackEntry.arguments?.getLong("taskId") ?: return@composable

            LaunchedEffect(taskId) {
                viewModel.clearEditingConfig()
                viewModel.loadTaskConfig(taskId)
            }

            val config by viewModel.editingConfig.collectAsState()

            val pickedPickup = backStackEntry.savedStateHandle.getStateFlow<String?>("picked_pickup", null)
                .collectAsState()
            val pickedDestination = backStackEntry.savedStateHandle.getStateFlow<String?>("picked_destination", null)
                .collectAsState()

            config?.let { existingConfig ->
                if (existingConfig.id != taskId) return@let
                TaskConfigScreen(
                    existingConfig = existingConfig,
                    pickedPickup = pickedPickup.value,
                    pickedDestination = pickedDestination.value,
                    onPickOnMap = { fieldKey -> navController.navigate(Routes.mapPicker(fieldKey)) },
                    onSave = { updated ->
                        viewModel.saveTaskConfig(updated)
                        viewModel.clearEditingConfig()
                        navController.popBackStack()
                    },
                    onDelete = {
                        viewModel.deleteTaskConfig(existingConfig)
                        viewModel.clearEditingConfig()
                        navController.popBackStack()
                    },
                    onBack = {
                        viewModel.clearEditingConfig()
                        navController.popBackStack()
                    }
                )
            }
        }

        composable(
            route = Routes.MAP_PICKER,
            arguments = listOf(navArgument("fieldKey") { type = NavType.StringType })
        ) { backStackEntry ->
            val fieldKey = backStackEntry.arguments?.getString("fieldKey") ?: "destination"
            val mapProvider by viewModel.mapProvider.collectAsState()
            val apiKey by viewModel.googleMapsApiKey.collectAsState()

            MapPickerScreen(
                mapProvider = mapProvider,
                googleMapsApiKey = apiKey,
                onLocationPicked = { plusCode ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("picked_$fieldKey", plusCode)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SETTINGS) {
            val savedLocations by viewModel.savedLocations.collectAsState()
            val autoEnableLocation by viewModel.autoEnableLocation.collectAsState()
            val debugMode by viewModel.debugMode.collectAsState()
            val autoBypassSomeoneElse by viewModel.autoBypassSomeoneElse.collectAsState()
            val overlayDurationSeconds by viewModel.overlayDurationSeconds.collectAsState()
            val showComparisonOverlay by viewModel.showComparisonOverlay.collectAsState()
            val autoCloseApps by viewModel.autoCloseApps.collectAsState()
            val defaultDecisionMode by viewModel.defaultDecisionMode.collectAsState()
            val notificationSound by viewModel.notificationSound.collectAsState()
            val preferredApp by viewModel.preferredApp.collectAsState()
            val mapProvider by viewModel.mapProvider.collectAsState()
            val googleMapsApiKey by viewModel.googleMapsApiKey.collectAsState()
            SettingsScreen(
                savedLocations = savedLocations,
                autoEnableLocation = autoEnableLocation,
                debugMode = debugMode,
                autoBypassSomeoneElse = autoBypassSomeoneElse,
                overlayDurationSeconds = overlayDurationSeconds,
                showComparisonOverlay = showComparisonOverlay,
                autoCloseApps = autoCloseApps,
                defaultDecisionMode = defaultDecisionMode,
                notificationSound = notificationSound,
                preferredApp = preferredApp,
                mapProvider = mapProvider,
                googleMapsApiKey = googleMapsApiKey,
                onAutoEnableLocationChange = { viewModel.setAutoEnableLocation(it) },
                onDebugModeChange = { viewModel.setDebugMode(it) },
                onAutoBypassSomeoneElseChange = { viewModel.setAutoBypassSomeoneElse(it) },
                onOverlayDurationChange = { viewModel.setOverlayDurationSeconds(it) },
                onShowComparisonOverlayChange = { viewModel.setShowComparisonOverlay(it) },
                onAutoCloseAppsChange = { viewModel.setAutoCloseApps(it) },
                onDefaultDecisionModeChange = { viewModel.setDefaultDecisionMode(it) },
                onNotificationSoundChange = { viewModel.setNotificationSound(it) },
                onPreferredAppChange = { viewModel.setPreferredApp(it) },
                onMapProviderChange = { viewModel.setMapProvider(it) },
                onGoogleMapsApiKeyChange = { viewModel.setGoogleMapsApiKey(it) },
                onAddLocation = { viewModel.addSavedLocation(it) },
                onDeleteLocation = { viewModel.deleteSavedLocation(it) },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
