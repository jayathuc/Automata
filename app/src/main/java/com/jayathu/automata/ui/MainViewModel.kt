package com.jayathu.automata.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jayathu.automata.data.db.AutomataDatabase
import com.jayathu.automata.data.model.SavedLocation
import com.jayathu.automata.data.model.TaskConfig
import com.jayathu.automata.data.repository.AutomataRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AutomataRepository(
        AutomataDatabase.getInstance(application)
    )

    val taskConfigs: StateFlow<List<TaskConfig>> = repository.getAllTaskConfigs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val savedLocations: StateFlow<List<SavedLocation>> = repository.getAllSavedLocations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _editingConfig = MutableStateFlow<TaskConfig?>(null)
    val editingConfig: StateFlow<TaskConfig?> = _editingConfig.asStateFlow()

    fun loadTaskConfig(id: Long) {
        viewModelScope.launch {
            _editingConfig.value = repository.getTaskConfig(id)
        }
    }

    fun clearEditingConfig() {
        _editingConfig.value = null
    }

    fun saveTaskConfig(config: TaskConfig) {
        viewModelScope.launch {
            if (config.id == 0L) {
                repository.saveTaskConfig(config)
            } else {
                repository.updateTaskConfig(config)
            }
        }
    }

    fun deleteTaskConfig(config: TaskConfig) {
        viewModelScope.launch {
            repository.deleteTaskConfig(config)
        }
    }

    fun addSavedLocation(location: SavedLocation) {
        viewModelScope.launch {
            repository.saveSavedLocation(location)
        }
    }

    fun deleteSavedLocation(location: SavedLocation) {
        viewModelScope.launch {
            repository.deleteSavedLocation(location)
        }
    }
}
