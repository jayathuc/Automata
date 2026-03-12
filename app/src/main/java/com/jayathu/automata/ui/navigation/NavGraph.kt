package com.jayathu.automata.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.jayathu.automata.ui.MainViewModel
import com.jayathu.automata.ui.screens.DashboardScreen
import com.jayathu.automata.ui.screens.SettingsScreen
import com.jayathu.automata.ui.screens.TaskConfigScreen

object Routes {
    const val DASHBOARD = "dashboard"
    const val NEW_TASK = "task/new"
    const val EDIT_TASK = "task/edit/{taskId}"
    const val SETTINGS = "settings"

    fun editTask(taskId: Long) = "task/edit/$taskId"
}

@Composable
fun AutomataNavGraph(
    navController: NavHostController,
    viewModel: MainViewModel
) {
    NavHost(navController = navController, startDestination = Routes.DASHBOARD) {
        composable(Routes.DASHBOARD) {
            val taskConfigs by viewModel.taskConfigs.collectAsState()
            DashboardScreen(
                taskConfigs = taskConfigs,
                onAddTask = { navController.navigate(Routes.NEW_TASK) },
                onEditTask = { id -> navController.navigate(Routes.editTask(id)) },
                onGoTask = { /* Phase 2+: launch automation */ },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) }
            )
        }

        composable(Routes.NEW_TASK) {
            TaskConfigScreen(
                existingConfig = null,
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
            viewModel.loadTaskConfig(taskId)
            val config by viewModel.editingConfig.collectAsState()

            config?.let { existingConfig ->
                TaskConfigScreen(
                    existingConfig = existingConfig,
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

        composable(Routes.SETTINGS) {
            val savedLocations by viewModel.savedLocations.collectAsState()
            SettingsScreen(
                savedLocations = savedLocations,
                onAddLocation = { viewModel.addSavedLocation(it) },
                onDeleteLocation = { viewModel.deleteSavedLocation(it) },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
