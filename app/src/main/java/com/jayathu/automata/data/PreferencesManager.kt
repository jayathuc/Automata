package com.jayathu.automata.data

import android.content.Context
import android.content.SharedPreferences
import com.jayathu.automata.data.model.DecisionMode

class PreferencesManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "automata_prefs"
        private const val KEY_AUTO_ENABLE_LOCATION = "auto_enable_location"
        private const val KEY_DEBUG_MODE = "debug_mode"
        private const val KEY_AUTO_BYPASS_SOMEONE_ELSE = "auto_bypass_someone_else"
        private const val KEY_OVERLAY_DURATION = "overlay_duration_seconds"
        private const val KEY_SHOW_COMPARISON_OVERLAY = "show_comparison_overlay"
        private const val KEY_AUTO_CLOSE_APPS = "auto_close_apps"
        private const val KEY_DEFAULT_DECISION_MODE = "default_decision_mode"
        private const val KEY_NOTIFICATION_SOUND = "notification_sound"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var autoEnableLocation: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ENABLE_LOCATION, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_ENABLE_LOCATION, value).apply()

    var debugMode: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_MODE, value).apply()

    var autoBypassSomeoneElse: Boolean
        get() = prefs.getBoolean(KEY_AUTO_BYPASS_SOMEONE_ELSE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_BYPASS_SOMEONE_ELSE, value).apply()

    var overlayDurationSeconds: Int
        get() = prefs.getInt(KEY_OVERLAY_DURATION, 8)
        set(value) = prefs.edit().putInt(KEY_OVERLAY_DURATION, value).apply()

    var showComparisonOverlay: Boolean
        get() = prefs.getBoolean(KEY_SHOW_COMPARISON_OVERLAY, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_COMPARISON_OVERLAY, value).apply()

    var autoCloseApps: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CLOSE_APPS, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CLOSE_APPS, value).apply()

    var defaultDecisionMode: DecisionMode
        get() = try {
            DecisionMode.valueOf(prefs.getString(KEY_DEFAULT_DECISION_MODE, DecisionMode.CHEAPEST.name)!!)
        } catch (_: Exception) {
            DecisionMode.CHEAPEST
        }
        set(value) = prefs.edit().putString(KEY_DEFAULT_DECISION_MODE, value.name).apply()

    var notificationSound: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_SOUND, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATION_SOUND, value).apply()
}
