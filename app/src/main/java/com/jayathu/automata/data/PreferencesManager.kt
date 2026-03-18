package com.jayathu.automata.data

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "automata_prefs"
        private const val KEY_AUTO_ENABLE_LOCATION = "auto_enable_location"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var autoEnableLocation: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ENABLE_LOCATION, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_ENABLE_LOCATION, value).apply()
}
