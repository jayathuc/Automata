package com.jayathu.automata.ui

import android.app.Application
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jayathu.automata.data.db.AutomataDatabase
import com.jayathu.automata.data.PreferencesManager
import com.jayathu.automata.data.model.DecisionMode
import com.jayathu.automata.data.model.MapProvider
import com.jayathu.automata.data.model.RideApp
import com.jayathu.automata.data.model.SavedLocation
import com.jayathu.automata.data.model.TaskConfig
import com.jayathu.automata.data.repository.AutomataRepository
import com.jayathu.automata.engine.AutomationEngine
import com.jayathu.automata.engine.AutomationResult
import com.jayathu.automata.engine.AutomationState
import com.jayathu.automata.notification.AutomationNotificationManager
import com.jayathu.automata.notification.AutomationControlOverlay
import com.jayathu.automata.notification.ComparisonOverlay
import com.jayathu.automata.engine.UiInspector
import com.jayathu.automata.scripts.RideOrchestrator
import com.jayathu.automata.service.AutomataAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AutomationUiState(
    val isRunning: Boolean = false,
    val currentStep: String = "",
    val result: AutomationResult? = null,
    val accessibilityEnabled: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val repository = AutomataRepository(
        AutomataDatabase.getInstance(application)
    )

    private val notificationManager = AutomationNotificationManager(application)
    private val preferencesManager = PreferencesManager(application)

    private val _autoEnableLocation = MutableStateFlow(true)
    val autoEnableLocation: StateFlow<Boolean> = _autoEnableLocation.asStateFlow()

    private val _debugMode = MutableStateFlow(false)
    val debugMode: StateFlow<Boolean> = _debugMode.asStateFlow()

    private val _autoBypassSomeoneElse = MutableStateFlow(true)
    val autoBypassSomeoneElse: StateFlow<Boolean> = _autoBypassSomeoneElse.asStateFlow()

    private val _overlayDurationSeconds = MutableStateFlow(8)
    val overlayDurationSeconds: StateFlow<Int> = _overlayDurationSeconds.asStateFlow()

    private val _showComparisonOverlay = MutableStateFlow(true)
    val showComparisonOverlay: StateFlow<Boolean> = _showComparisonOverlay.asStateFlow()

    private val _autoCloseApps = MutableStateFlow(false)
    val autoCloseApps: StateFlow<Boolean> = _autoCloseApps.asStateFlow()

    private val _defaultDecisionMode = MutableStateFlow(DecisionMode.CHEAPEST)
    val defaultDecisionMode: StateFlow<DecisionMode> = _defaultDecisionMode.asStateFlow()

    private val _notificationSound = MutableStateFlow(true)
    val notificationSound: StateFlow<Boolean> = _notificationSound.asStateFlow()

    private val _preferredApp = MutableStateFlow(RideApp.PICKME)
    val preferredApp: StateFlow<RideApp> = _preferredApp.asStateFlow()

    private val _showRunWarning = MutableStateFlow(true)
    val showRunWarning: StateFlow<Boolean> = _showRunWarning.asStateFlow()

    private val _mapProvider = MutableStateFlow(MapProvider.OPENSTREETMAP)
    val mapProvider: StateFlow<MapProvider> = _mapProvider.asStateFlow()

    private val _googleMapsApiKey = MutableStateFlow("")
    val googleMapsApiKey: StateFlow<String> = _googleMapsApiKey.asStateFlow()

    private var controlOverlay: AutomationControlOverlay? = null
    @Volatile private var isAborting = false

    fun setAutoEnableLocation(enabled: Boolean) {
        preferencesManager.autoEnableLocation = enabled
        _autoEnableLocation.value = enabled
    }

    fun setDebugMode(enabled: Boolean) {
        preferencesManager.debugMode = enabled
        _debugMode.value = enabled
    }

    fun setAutoBypassSomeoneElse(enabled: Boolean) {
        preferencesManager.autoBypassSomeoneElse = enabled
        _autoBypassSomeoneElse.value = enabled
    }

    fun setOverlayDurationSeconds(seconds: Int) {
        preferencesManager.overlayDurationSeconds = seconds
        _overlayDurationSeconds.value = seconds
    }

    fun setShowComparisonOverlay(enabled: Boolean) {
        preferencesManager.showComparisonOverlay = enabled
        _showComparisonOverlay.value = enabled
    }

    fun setAutoCloseApps(enabled: Boolean) {
        preferencesManager.autoCloseApps = enabled
        _autoCloseApps.value = enabled
    }

    fun setDefaultDecisionMode(mode: DecisionMode) {
        preferencesManager.defaultDecisionMode = mode
        _defaultDecisionMode.value = mode
    }

    fun setNotificationSound(enabled: Boolean) {
        preferencesManager.notificationSound = enabled
        _notificationSound.value = enabled
    }

    fun setPreferredApp(app: RideApp) {
        preferencesManager.preferredApp = app
        _preferredApp.value = app
    }

    fun setShowRunWarning(show: Boolean) {
        preferencesManager.showRunWarning = show
        _showRunWarning.value = show
    }

    fun setMapProvider(provider: MapProvider) {
        preferencesManager.mapProvider = provider
        _mapProvider.value = provider
    }

    fun setGoogleMapsApiKey(key: String) {
        preferencesManager.googleMapsApiKey = key
        _googleMapsApiKey.value = key
    }

    val taskConfigs: StateFlow<List<TaskConfig>> = repository.getAllTaskConfigs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val savedLocations: StateFlow<List<SavedLocation>> = repository.getAllSavedLocations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _editingConfig = MutableStateFlow<TaskConfig?>(null)
    val editingConfig: StateFlow<TaskConfig?> = _editingConfig.asStateFlow()

    private val _automationState = MutableStateFlow(AutomationUiState())
    val automationState: StateFlow<AutomationUiState> = _automationState.asStateFlow()

    init {
        _autoEnableLocation.value = preferencesManager.autoEnableLocation
        _debugMode.value = preferencesManager.debugMode
        _autoBypassSomeoneElse.value = preferencesManager.autoBypassSomeoneElse
        _overlayDurationSeconds.value = preferencesManager.overlayDurationSeconds
        _showComparisonOverlay.value = preferencesManager.showComparisonOverlay
        _autoCloseApps.value = preferencesManager.autoCloseApps
        _defaultDecisionMode.value = preferencesManager.defaultDecisionMode
        _notificationSound.value = preferencesManager.notificationSound
        _preferredApp.value = preferencesManager.preferredApp
        _showRunWarning.value = preferencesManager.showRunWarning
        _mapProvider.value = preferencesManager.mapProvider
        _googleMapsApiKey.value = preferencesManager.googleMapsApiKey

        // Observe accessibility service state changes for notification updates.
        // Using collectLatest ensures the inner collector is cancelled when the
        // service value changes (e.g., service disconnects/reconnects), preventing
        // orphaned collectors that leave stale notifications.
        viewModelScope.launch {
            AutomataAccessibilityService.instance.collectLatest { service ->
                _automationState.value = _automationState.value.copy(
                    accessibilityEnabled = service != null
                )
                if (service == null) {
                    // Service disconnected — dismiss any lingering progress notification
                    notificationManager.updateFromState(AutomationState.Idle)
                    return@collectLatest
                }
                service.getEngine()?.state?.collect { engineState ->
                    // Skip notification updates after abort to prevent trailing
                    // engine emissions from overwriting the abort notification
                    if (!isAborting) {
                        notificationManager.updateFromState(engineState)
                    }
                    _automationState.value = _automationState.value.copy(
                        currentStep = when (engineState) {
                            is AutomationState.Running -> engineState.stepName
                            is AutomationState.WaitingForUI -> engineState.stepName
                            is AutomationState.StepComplete -> engineState.stepName
                            is AutomationState.Error -> "Error: ${engineState.reason}"
                            is AutomationState.Done -> "Complete"
                            is AutomationState.Aborted -> "Aborted"
                            is AutomationState.Idle -> ""
                        }
                    )
                }
            }
        }
    }

    fun runAutomation(config: TaskConfig) {
        if (_automationState.value.isRunning) {
            Log.w(TAG, "Automation already running")
            return
        }
        isAborting = false

        val service = AutomataAccessibilityService.instance.value
        if (service == null) {
            Log.w(TAG, "Accessibility service not enabled")
            _automationState.value = _automationState.value.copy(
                result = AutomationResult.Failed("", "Accessibility service not enabled. Enable it in Settings > Accessibility > Automata.", emptyMap())
            )
            return
        }

        val engine = service.getEngine() ?: return
        val context = getApplication<Application>()

        // Pre-flight validation
        if (config.destinationAddress.isBlank()) {
            _automationState.value = _automationState.value.copy(
                result = AutomationResult.Failed("", "Destination address is empty. Edit the task to add a destination.", emptyMap())
            )
            return
        }

        if (!config.enablePickMe && !config.enableUber) {
            _automationState.value = _automationState.value.copy(
                result = AutomationResult.Failed("", "No apps enabled for this task. Enable at least one app.", emptyMap())
            )
            return
        }

        // Check if enabled apps are installed
        val errors = mutableListOf<String>()
        if (config.enablePickMe && !AutomationEngine.isAppInstalled(context, com.jayathu.automata.data.model.RideApp.PICKME.packageName)) {
            errors.add("PickMe is not installed")
        }
        if (config.enableUber && !AutomationEngine.isAppInstalled(context, com.jayathu.automata.data.model.RideApp.UBER.packageName)) {
            errors.add("Uber is not installed")
        }
        if (errors.isNotEmpty()) {
            _automationState.value = _automationState.value.copy(
                result = AutomationResult.Failed("", errors.joinToString(". ") + ".", emptyMap())
            )
            return
        }

        // Check network connectivity
        if (!isNetworkAvailable(context)) {
            _automationState.value = _automationState.value.copy(
                result = AutomationResult.Failed("", "No internet connection. Connect to Wi-Fi or mobile data and try again.", emptyMap())
            )
            return
        }

        // Check location services (only block if auto-enable is off — the script handles it when on)
        if (!isLocationEnabled(context) && !_autoEnableLocation.value) {
            _automationState.value = _automationState.value.copy(
                result = AutomationResult.Failed("", "Location services are turned off. Turn on Location in your phone settings or enable auto-enable in Settings.", emptyMap())
            )
            return
        }

        _automationState.value = _automationState.value.copy(
            isRunning = true,
            result = null,
            currentStep = "Starting..."
        )

        val showOverlay = _showComparisonOverlay.value
        val overlayDuration = _overlayDurationSeconds.value
        val soundEnabled = _notificationSound.value
        val autoClose = _autoCloseApps.value
        val bypassSomeoneElse = _autoBypassSomeoneElse.value

        val preferredApp = _preferredApp.value
        val comparisonOverlay = if (showOverlay) ComparisonOverlay(service, overlayDuration * 1000L) else null
        val orchestrator = RideOrchestrator(
            context = context,
            autoBypassSomeoneElse = bypassSomeoneElse,
            autoCloseApps = autoClose,
            preferredApp = preferredApp,
            onComparisonReady = { comparisonData ->
                comparisonOverlay?.show(comparisonData)
                notificationManager.showComparisonPopup(comparisonData, soundEnabled)
            }
        )
        val steps = orchestrator.buildSteps(config)

        // Show floating control pill with timer and stop button
        val control = AutomationControlOverlay(service) { abortAutomation() }
        controlOverlay = control
        control.show()

        engine.runAutomation(steps) { result ->
            Log.i(TAG, "Automation result: $result")
            isAborting = false
            control.dismiss()
            controlOverlay = null
            // Ensure progress notification is dismissed regardless of flow timing
            notificationManager.updateFromState(AutomationState.Idle)
            _automationState.value = _automationState.value.copy(
                isRunning = false,
                result = result
            )
        }
    }

    fun abortAutomation() {
        val service = AutomataAccessibilityService.instance.value ?: return
        isAborting = true
        service.getEngine()?.abort()
        controlOverlay?.dismiss()
        controlOverlay = null
        // Explicitly dismiss the progress notification — don't rely on the
        // engine state flow to emit a terminal state in time.
        notificationManager.updateFromState(AutomationState.Aborted)
        _automationState.value = _automationState.value.copy(
            isRunning = false,
            result = AutomationResult.Aborted
        )
    }

    fun clearResult() {
        _automationState.value = _automationState.value.copy(result = null)
    }

    fun checkAccessibility(): Boolean {
        val context = getApplication<Application>()
        val enabled = AutomationEngine.isAccessibilityEnabled(context)
        _automationState.value = _automationState.value.copy(accessibilityEnabled = enabled)
        return enabled
    }

    fun openAccessibilitySettings() {
        AutomationEngine.openAccessibilitySettings(getApplication())
    }

    private val _dumpCountdown = MutableStateFlow(-1)
    val dumpCountdown: StateFlow<Int> = _dumpCountdown.asStateFlow()

    fun dumpCurrentUi() {
        val service = AutomataAccessibilityService.instance.value
        if (service == null) {
            Log.w(TAG, "Cannot dump UI: accessibility service not enabled")
            return
        }

        viewModelScope.launch {
            // 5-second countdown so user can switch to target app
            for (i in 5 downTo 1) {
                _dumpCountdown.value = i
                Log.i(TAG, "UI dump in $i seconds... Switch to the target app now!")
                kotlinx.coroutines.delay(1000)
            }
            _dumpCountdown.value = 0

            val root = service.getRootNode()
            if (root == null) {
                Log.w(TAG, "Cannot dump UI: no root node available")
            } else {
                Log.i(TAG, "Dumping UI tree now")
                UiInspector.dumpCurrentScreen(root)
            }

            kotlinx.coroutines.delay(500)
            _dumpCountdown.value = -1
        }
    }

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

    suspend fun saveTaskConfigAndGetId(config: TaskConfig): Long {
        return if (config.id == 0L) {
            repository.saveTaskConfig(config)
        } else {
            repository.updateTaskConfig(config)
            config.id
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

    private fun isNetworkAvailable(context: android.content.Context): Boolean {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun isLocationEnabled(context: android.content.Context): Boolean {
        val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
}
