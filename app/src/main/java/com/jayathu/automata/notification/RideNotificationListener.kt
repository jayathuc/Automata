package com.jayathu.automata.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.jayathu.automata.data.model.RideApp
import com.jayathu.automata.engine.SecureLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RideConfirmation(
    val app: RideApp,
    val driverName: String?,
    val vehicleInfo: String?,
    val rawText: String
)

class RideNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "RideNotifListener"

        private val _confirmation = MutableStateFlow<RideConfirmation?>(null)
        val confirmation: StateFlow<RideConfirmation?> = _confirmation.asStateFlow()

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        fun clearConfirmation() {
            _confirmation.value = null
        }

        // Keywords that indicate a ride has been confirmed
        private val PICKME_CONFIRM_KEYWORDS = listOf(
            "driver is on the way",
            "ride confirmed",
            "your driver",
            "arriving",
            "is heading to you"
        )

        private val UBER_CONFIRM_KEYWORDS = listOf(
            "driver is on the way",
            "ride confirmed",
            "your driver",
            "arriving",
            "is heading your way"
        )
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        _isConnected.value = true
        Log.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        _isConnected.value = false
        Log.i(TAG, "Notification listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val packageName = sbn.packageName

        val app = when (packageName) {
            RideApp.PICKME.packageName -> RideApp.PICKME
            RideApp.UBER.packageName -> RideApp.UBER
            else -> return // Not a ride app notification
        }

        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val fullText = "$title $text".lowercase()

        SecureLog.verbose(TAG, "Notification from ${app.displayName}: title=$title, text=$text")

        val keywords = when (app) {
            RideApp.PICKME -> PICKME_CONFIRM_KEYWORDS
            RideApp.UBER -> UBER_CONFIRM_KEYWORDS
        }

        if (keywords.any { fullText.contains(it) }) {
            Log.i(TAG, "Ride confirmation detected from ${app.displayName}!")
            _confirmation.value = RideConfirmation(
                app = app,
                driverName = extractDriverName(title, text),
                vehicleInfo = null,
                rawText = "$title: $text"
            )
        }
    }

    private fun extractDriverName(title: String, text: String): String? {
        // Simple heuristic: driver name is often in the title
        return if (title.isNotBlank() && !title.contains("ride", ignoreCase = true)) {
            title
        } else {
            null
        }
    }
}
