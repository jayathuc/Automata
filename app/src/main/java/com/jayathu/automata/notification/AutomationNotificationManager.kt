package com.jayathu.automata.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.jayathu.automata.MainActivity
import com.jayathu.automata.R
import com.jayathu.automata.engine.AutomationState
import com.jayathu.automata.engine.ErrorMapper

class AutomationNotificationManager(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "automata_status"
        private const val RESULT_CHANNEL_ID = "automata_result"
        private const val RESULT_SILENT_CHANNEL_ID = "automata_result_silent"
        private const val NOTIFICATION_ID = 1001
        private const val RESULT_NOTIFICATION_ID = 1002
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createChannel()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Automation Status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows the current automation progress"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)

        val resultChannel = NotificationChannel(
            RESULT_CHANNEL_ID,
            "Automation Results",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Shows price comparison results with sound"
            setShowBadge(true)
        }
        notificationManager.createNotificationChannel(resultChannel)

        val resultSilentChannel = NotificationChannel(
            RESULT_SILENT_CHANNEL_ID,
            "Automation Results (Silent)",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Shows price comparison results without sound"
            setShowBadge(true)
            setSound(null, null)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(resultSilentChannel)
    }

    fun updateFromState(state: AutomationState) {
        when (state) {
            is AutomationState.Idle -> dismiss()
            is AutomationState.Running -> show(
                "Running: ${state.stepName}",
                "Step ${state.stepIndex + 1} of ${state.totalSteps}",
                ongoing = true,
                progress = Pair(state.stepIndex, state.totalSteps)
            )
            is AutomationState.WaitingForUI -> show(
                "Waiting: ${state.stepName}",
                "Looking for UI elements...",
                ongoing = true
            )
            is AutomationState.StepComplete -> show(
                "Completed: ${state.stepName}",
                "Step ${state.stepIndex + 1} of ${state.totalSteps} done",
                ongoing = true,
                progress = Pair(state.stepIndex + 1, state.totalSteps)
            )
            is AutomationState.Error -> {
                dismiss() // Remove progress notification
                val friendly = ErrorMapper.map(state.stepName, state.reason)
                val body = if (friendly.suggestion.isNotBlank())
                    "${friendly.message}\n${friendly.suggestion}"
                else
                    friendly.message
                showHighPriority(friendly.title, body)
            }
            is AutomationState.Done -> {
                dismiss() // Remove progress notification
                showResult(state.collectedData)
            }
            is AutomationState.Aborted -> {
                dismiss() // Remove progress notification
                showHighPriority(
                    "Automation Aborted",
                    "The automation was stopped"
                )
            }
        }
    }

    private fun show(
        title: String,
        text: String,
        ongoing: Boolean,
        progress: Pair<Int, Int>? = null
    ) {
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(ongoing)
            .setSilent(true)

        if (progress != null) {
            builder.setProgress(progress.second, progress.first, false)
        }

        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    fun dismiss() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    /**
     * Show a high-priority notification for errors/aborts so the user actually sees it.
     */
    private fun showHighPriority(title: String, text: String) {
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        notificationManager.notify(RESULT_NOTIFICATION_ID, builder.build())
    }

    /**
     * Show a heads-up pop-up notification with comparison results.
     * Called right after prices are compared (before booking) so the user
     * can see the winner without opening the app.
     */
    fun showComparisonPopup(data: Map<String, String>, soundEnabled: Boolean = true) {
        val pickMePrice = data["pickme_price"]
        val uberPrice = data["uber_price"]
        val winner = data["winner"]
        val winnerSummary = data["winner_summary"]
        val pickMeEta = data["pickme_eta"]
        val uberEta = data["uber_eta"]

        val title = if (winnerSummary != null) {
            "Booking $winnerSummary"
        } else if (winner != null) {
            "Booking $winner"
        } else {
            "Price Comparison"
        }

        val lines = mutableListOf<String>()
        if (pickMePrice != null) {
            val etaSuffix = if (pickMeEta != null) " (${pickMeEta} min)" else ""
            lines.add("PickMe: Rs $pickMePrice$etaSuffix")
        }
        if (uberPrice != null) {
            val etaSuffix = if (uberEta != null) " (${uberEta} min)" else ""
            lines.add("Uber: Rs $uberPrice$etaSuffix")
        }

        val pickMe = pickMePrice?.toDoubleOrNull()
        val uber = uberPrice?.toDoubleOrNull()
        if (pickMe != null && uber != null) {
            val savings = kotlin.math.abs(pickMe - uber)
            lines.add("You save Rs ${String.format("%.0f", savings)}")
        }

        val summary = if (lines.isEmpty()) "Comparing..." else lines.joinToString("\n")

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = if (soundEnabled) RESULT_CHANNEL_ID else RESULT_SILENT_CHANNEL_ID

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(title)
            .setContentText(lines.firstOrNull() ?: "Done")
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setFullScreenIntent(pendingIntent, true)

        notificationManager.notify(RESULT_NOTIFICATION_ID, builder.build())
    }

    private fun showResult(data: Map<String, String>) {
        // Final notification reuses the same ID so it updates the comparison popup
        showComparisonPopup(data)
    }
}
