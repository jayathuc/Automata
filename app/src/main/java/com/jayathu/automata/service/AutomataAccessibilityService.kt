package com.jayathu.automata.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jayathu.automata.engine.AutomationEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AutomataAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AutomataA11y"

        /** Packages the service is allowed to interact with */
        val ALLOWED_PACKAGES = setOf(
            "com.pickme.passenger",
            "com.ubercab",
            // System packages needed for navigation (home screen, settings, launcher)
            "com.android.launcher",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher",       // Samsung
            "com.miui.home",                       // Xiaomi
            "com.huawei.android.launcher",         // Huawei
            "com.android.settings",
            "android",                             // System UI / dialogs
            "com.android.systemui"
        )

        private val _instance = MutableStateFlow<AutomataAccessibilityService?>(null)
        val instance: StateFlow<AutomataAccessibilityService?> = _instance.asStateFlow()

        val isRunning: Boolean
            get() = _instance.value != null
    }

    private var engine: AutomationEngine? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        _instance.value = this
        engine = AutomationEngine(this)
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // Only forward events from allowed packages to the engine
        val pkg = event.packageName?.toString() ?: return
        if (pkg in ALLOWED_PACKAGES) {
            engine?.onAccessibilityEvent(event)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        engine?.shutdown()
        engine = null
        _instance.value = null
        Log.i(TAG, "Accessibility service destroyed")
        super.onDestroy()
    }

    fun getEngine(): AutomationEngine? = engine

    fun getRootNode(): AccessibilityNodeInfo? = rootInActiveWindow
}
