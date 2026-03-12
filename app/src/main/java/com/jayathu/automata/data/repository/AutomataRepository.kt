package com.jayathu.automata.data.repository

import com.jayathu.automata.data.db.AutomataDatabase
import com.jayathu.automata.data.model.SavedLocation
import com.jayathu.automata.data.model.TaskConfig
import kotlinx.coroutines.flow.Flow

class AutomataRepository(private val database: AutomataDatabase) {

    // Task Configs
    fun getAllTaskConfigs(): Flow<List<TaskConfig>> = database.taskConfigDao().getAll()

    suspend fun getTaskConfig(id: Long): TaskConfig? = database.taskConfigDao().getById(id)

    suspend fun saveTaskConfig(config: TaskConfig): Long = database.taskConfigDao().insert(config)

    suspend fun updateTaskConfig(config: TaskConfig) = database.taskConfigDao().update(config)

    suspend fun deleteTaskConfig(config: TaskConfig) = database.taskConfigDao().delete(config)

    // Saved Locations
    fun getAllSavedLocations(): Flow<List<SavedLocation>> = database.savedLocationDao().getAll()

    suspend fun saveSavedLocation(location: SavedLocation): Long =
        database.savedLocationDao().insert(location)

    suspend fun updateSavedLocation(location: SavedLocation) =
        database.savedLocationDao().update(location)

    suspend fun deleteSavedLocation(location: SavedLocation) =
        database.savedLocationDao().delete(location)
}
