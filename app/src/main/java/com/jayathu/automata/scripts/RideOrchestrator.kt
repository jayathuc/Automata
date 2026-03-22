package com.jayathu.automata.scripts

import android.content.Context
import android.util.Log
import com.jayathu.automata.data.model.DecisionMode
import com.jayathu.automata.data.model.RideApp
import com.jayathu.automata.data.model.TaskConfig
import com.jayathu.automata.engine.AutomationEngine
import com.jayathu.automata.engine.AutomationStep
import com.jayathu.automata.engine.StepResult
import com.jayathu.automata.service.AutomataAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class OrchestratorResult(
    val pickMePrice: Double? = null,
    val uberPrice: Double? = null,
    val pickMeEta: Int? = null,
    val uberEta: Int? = null,
    val winner: RideApp? = null,
    val error: String? = null
)

class RideOrchestrator(
    private val context: Context,
    private val autoBypassSomeoneElse: Boolean = true,
    private val autoCloseApps: Boolean = false,
    private val preferredApp: RideApp = RideApp.PICKME,
    private val onComparisonReady: ((Map<String, String>) -> Unit)? = null
) {

    companion object {
        private const val TAG = "RideOrchestrator"
    }

    private val _result = MutableStateFlow(OrchestratorResult())
    val result: StateFlow<OrchestratorResult> = _result.asStateFlow()

    /**
     * Builds a combined step list that:
     * 1. Reads prices from enabled apps
     * 2. Compares prices and determines the winner
     * 3. Re-opens the winning app and books the ride
     */
    fun buildSteps(config: TaskConfig): List<AutomationStep> {
        Log.i(TAG, "Building steps: enablePickMe=${config.enablePickMe}, enableUber=${config.enableUber}, mode=${config.decisionMode}")

        val steps = mutableListOf<AutomationStep>()

        val pickup = config.pickupAddress

        // Phase 0a: Warm up both apps (launch briefly to trigger process creation)
        if (config.enablePickMe && config.enableUber) {
            steps.add(warmUpApps(config))
        }

        // Phase 0b: Force-close both apps so they start from a clean state
        steps.add(forceCloseApps(config))

        // Phase 1: Read prices from both apps
        if (config.enablePickMe) {
            steps.addAll(PickMeScript.buildSteps(context, config.destinationAddress, config.rideType, pickup))
            steps.add(returnToHome())
        }

        if (config.enableUber) {
            steps.addAll(UberScript.buildSteps(context, config.destinationAddress, config.rideType, pickup))
            steps.add(returnToHome())
        }

        // Phase 2: Compare prices
        if (config.enablePickMe && config.enableUber) {
            steps.add(comparePrices(config.decisionMode))
        } else if (config.enablePickMe) {
            steps.add(setWinner(RideApp.PICKME))
        } else {
            steps.add(setWinner(RideApp.UBER))
        }

        // Phase 3: Book the winner
        // The comparison overlay auto-dismisses after 8s and is hidden during
        // screenshots so it doesn't interfere with OCR-based booking taps.
        if (config.enablePickMe) {
            steps.addAll(buildConditionalBookingSteps(
                RideApp.PICKME,
                PickMeScript.buildQuickBookingSteps(context)
            ))
        }
        if (config.enableUber) {
            steps.addAll(buildConditionalBookingSteps(
                RideApp.UBER,
                UberScript.buildQuickBookingSteps(context, config.rideType, config.destinationAddress, autoBypassSomeoneElse)
            ))
        }

        // Phase 4: Auto-close apps after booking (if enabled)
        if (autoCloseApps) {
            steps.add(closeAppsAfterBooking(config))
        }

        return steps
    }

    /**
     * Wraps each booking step so it only runs if this app is the winner.
     * Every step independently checks collectedData["winner"] — the single
     * source of truth — so the decision never depends on mutable closure state.
     */
    private fun buildConditionalBookingSteps(
        app: RideApp,
        bookingSteps: List<AutomationStep>
    ): List<AutomationStep> {
        // Flag used ONLY by waitCondition (which can't access stepContext) to
        // short-circuit waits for the non-winner.  The action always re-verifies
        // against collectedData["winner"] independently.
        val skipWait = object { @Volatile var value = false }

        val gateStep = AutomationStep(
            name = "[${app.displayName}] Check winner",
            waitCondition = { true },
            timeoutMs = 2_000,
            delayAfterMs = 0,
            action = { _, stepContext ->
                val winner = stepContext.collectedData["winner"]
                val isWinner = (winner == app.displayName)
                skipWait.value = !isWinner
                Log.i(TAG, "[${app.displayName}] Gate check: collectedData[winner]='$winner', " +
                        "app.displayName='${app.displayName}', isWinner=$isWinner")
                if (isWinner) {
                    StepResult.Success
                } else {
                    StepResult.Skip("Not the winner (winner=$winner)")
                }
            }
        )

        val wrappedSteps = bookingSteps.map { step ->
            AutomationStep(
                name = "[${app.displayName}] ${step.name}",
                waitCondition = { root ->
                    if (skipWait.value) {
                        true // Not the winner — pass through so action can skip
                    } else {
                        step.waitCondition(root) // Winner — use original wait
                    }
                },
                timeoutMs = step.timeoutMs,
                delayAfterMs = step.delayAfterMs,
                delayBeforeMs = step.delayBeforeMs,
                maxRetries = step.maxRetries,
                action = { root, stepContext ->
                    // Always verify against the authoritative source
                    val winner = stepContext.collectedData["winner"]
                    if (winner != app.displayName) {
                        Log.d(TAG, "[${app.displayName}] Skipping '${step.name}': winner=$winner")
                        StepResult.Skip("Not the winner (winner=$winner)")
                    } else {
                        step.action(root, stepContext)
                    }
                }
            )
        }

        return listOf(gateStep) + wrappedSteps
    }

    /**
     * Warm up the SECOND app by launching it briefly. This loads app code into
     * the filesystem page cache so the subsequent launch is faster.
     * Only the second app benefits — the first app cold-starts regardless.
     * PickMe runs first, so we warm up Uber (and vice versa).
     */
    private fun warmUpApps(config: TaskConfig) = AutomationStep(
        name = "Warm up second app",
        waitCondition = { true },
        timeoutMs = 8_000,
        action = { _, _ ->
            val service = AutomataAccessibilityService.instance.value
            if (service != null) {
                // PickMe runs first in the script, so warm up Uber (the second app)
                // If only one app is enabled, this step is skipped entirely (see buildSteps)
                val secondApp = if (config.enablePickMe) RideApp.UBER else RideApp.PICKME
                Log.i(TAG, "Warming up ${secondApp.displayName} (runs second)")
                AutomationEngine.launchApp(context, secondApp.packageName)
                kotlinx.coroutines.delay(1500)
                com.jayathu.automata.engine.ActionExecutor.pressHome(service)
                kotlinx.coroutines.delay(300)
                Log.i(TAG, "${secondApp.displayName} warmed up, code cached in memory")
                StepResult.Success
            } else {
                StepResult.Failure("Accessibility service not available")
            }
        }
    )

    /**
     * Force-close PickMe and Uber so they start fresh from their home screens.
     */
    private fun forceCloseApps(config: TaskConfig) = AutomationStep(
        name = "Force-close apps",
        waitCondition = { true },
        timeoutMs = 5_000,
        delayAfterMs = 1500,
        action = { _, _ ->
            if (config.enablePickMe) {
                AutomationEngine.forceCloseApp(context, RideApp.PICKME.packageName)
            }
            if (config.enableUber) {
                AutomationEngine.forceCloseApp(context, RideApp.UBER.packageName)
            }
            // Press home to ensure the launcher is the active window.
            // Without this, rootInActiveWindow may return null after killing processes,
            // causing the next step to timeout waiting for a root node.
            val service = AutomataAccessibilityService.instance.value
            if (service != null) {
                com.jayathu.automata.engine.ActionExecutor.pressHome(service)
            }
            Log.i(TAG, "Force-closed enabled apps, ready for fresh start")
            StepResult.Success
        }
    )

    private fun returnToHome() = AutomationStep(
        name = "Return to home screen",
        waitCondition = { true },
        timeoutMs = 3_000,
        delayAfterMs = 500,
        action = { _, _ ->
            val service = AutomataAccessibilityService.instance.value
            if (service != null) {
                com.jayathu.automata.engine.ActionExecutor.pressHome(service)
                StepResult.Success
            } else {
                StepResult.Failure("Accessibility service not available")
            }
        }
    )

    private fun closeAppsAfterBooking(config: TaskConfig) = AutomationStep(
        name = "Close apps after booking",
        waitCondition = { true },
        timeoutMs = 5_000,
        delayBeforeMs = 3000,
        action = { _, _ ->
            if (config.enablePickMe) {
                AutomationEngine.forceCloseApp(context, RideApp.PICKME.packageName)
            }
            if (config.enableUber) {
                AutomationEngine.forceCloseApp(context, RideApp.UBER.packageName)
            }
            Log.i(TAG, "Auto-closed apps after booking")
            StepResult.Success
        }
    )

    private fun setWinner(app: RideApp) = AutomationStep(
        name = "Set winner: ${app.displayName}",
        waitCondition = { true },
        timeoutMs = 2_000,
        action = { _, stepContext ->
            stepContext.collectedData["winner"] = app.displayName
            Log.i(TAG, "Only ${app.displayName} enabled, setting as winner")
            onComparisonReady?.invoke(stepContext.collectedData.toMap())
            StepResult.SuccessWithData("winner", app.displayName)
        }
    )

    private fun comparePrices(decisionMode: DecisionMode) = AutomationStep(
        name = "Compare prices",
        waitCondition = { true },
        timeoutMs = 2_000,
        action = { _, stepContext ->
            val pickMeRaw = stepContext.collectedData["pickme_price"]
            val uberRaw = stepContext.collectedData["uber_price"]
            val pickMeEtaRaw = stepContext.collectedData["pickme_eta"]
            val uberEtaRaw = stepContext.collectedData["uber_eta"]

            var pickMePrice = pickMeRaw?.toDoubleOrNull()
            var uberPrice = uberRaw?.toDoubleOrNull()

            // Sanity check: Sri Lankan ride prices are typically 100–99,999 LKR.
            // A price below 50 is almost certainly an OCR misread (e.g., comma read as dot).
            if (pickMePrice != null && pickMePrice < 50) {
                Log.w(TAG, "PickMe price suspiciously low ($pickMePrice from '$pickMeRaw'), discarding")
                pickMePrice = null
            }
            if (uberPrice != null && uberPrice < 50) {
                Log.w(TAG, "Uber price suspiciously low ($uberPrice from '$uberRaw'), discarding")
                uberPrice = null
            }
            val pickMeEta = pickMeEtaRaw?.toIntOrNull()
            val uberEta = uberEtaRaw?.toIntOrNull()

            Log.i(TAG, "Price comparison - PickMe: raw='$pickMeRaw' parsed=$pickMePrice, Uber: raw='$uberRaw' parsed=$uberPrice")
            Log.i(TAG, "ETA comparison - PickMe: ${pickMeEta ?: "N/A"} min, Uber: ${uberEta ?: "N/A"} min")
            Log.i(TAG, "Decision mode: $decisionMode")

            val pricesEqual = pickMePrice != null && uberPrice != null && pickMePrice == uberPrice
            val etasEqual = pickMeEta != null && uberEta != null && pickMeEta == uberEta

            data class Decision(val winner: RideApp, val reason: String)

            val decision: Decision? = when {
                pickMePrice != null && uberPrice != null -> {
                    when (decisionMode) {
                        DecisionMode.CHEAPEST -> {
                            when {
                                pickMePrice < uberPrice -> Decision(RideApp.PICKME, "save Rs ${String.format("%.0f", uberPrice - pickMePrice)}")
                                uberPrice < pickMePrice -> Decision(RideApp.UBER, "save Rs ${String.format("%.0f", pickMePrice - uberPrice)}")
                                // Prices tied, fall back to fastest
                                pickMeEta != null && uberEta != null && pickMeEta < uberEta ->
                                    Decision(RideApp.PICKME, "same price, ${uberEta - pickMeEta} min faster")
                                pickMeEta != null && uberEta != null && uberEta < pickMeEta ->
                                    Decision(RideApp.UBER, "same price, ${pickMeEta - uberEta} min faster")
                                // Both price and ETA tied (or no ETA), use preferred app
                                else -> Decision(preferredApp, if (etasEqual) "same price and ETA" else "same price")
                            }
                        }
                        DecisionMode.FASTEST -> {
                            when {
                                pickMeEta != null && uberEta != null && pickMeEta < uberEta ->
                                    Decision(RideApp.PICKME, "${uberEta - pickMeEta} min faster")
                                pickMeEta != null && uberEta != null && uberEta < pickMeEta ->
                                    Decision(RideApp.UBER, "${pickMeEta - uberEta} min faster")
                                // ETAs tied, fall back to cheapest
                                etasEqual && pickMePrice < uberPrice ->
                                    Decision(RideApp.PICKME, "same ETA, save Rs ${String.format("%.0f", uberPrice - pickMePrice)}")
                                etasEqual && uberPrice < pickMePrice ->
                                    Decision(RideApp.UBER, "same ETA, save Rs ${String.format("%.0f", pickMePrice - uberPrice)}")
                                // Both ETA and price tied, use preferred app
                                etasEqual && pricesEqual ->
                                    Decision(preferredApp, "same ETA and price")
                                // Only one ETA available
                                pickMeEta != null -> Decision(RideApp.PICKME, "only ETA available")
                                uberEta != null -> Decision(RideApp.UBER, "only ETA available")
                                // No ETA from either, fall back to cheapest
                                else -> {
                                    Log.i(TAG, "No ETA available, falling back to cheapest")
                                    if (pickMePrice <= uberPrice) Decision(RideApp.PICKME, "no ETA, cheapest")
                                    else Decision(RideApp.UBER, "no ETA, cheapest")
                                }
                            }
                        }
                    }
                }
                pickMePrice != null -> {
                    Log.i(TAG, "Only PickMe price available ($pickMePrice), Uber price missing")
                    Decision(RideApp.PICKME, "only option")
                }
                uberPrice != null -> {
                    Log.i(TAG, "Only Uber price available ($uberPrice), PickMe price missing")
                    Decision(RideApp.UBER, "only option")
                }
                else -> null
            }

            val winner = decision?.winner

            _result.value = OrchestratorResult(
                pickMePrice = pickMePrice,
                uberPrice = uberPrice,
                pickMeEta = pickMeEta,
                uberEta = uberEta,
                winner = winner
            )

            if (decision != null) {
                val winnerName = decision.winner.displayName
                val reason = decision.reason

                stepContext.collectedData["winner"] = winnerName
                stepContext.collectedData["winner_summary"] = "$winnerName ($reason)"
                stepContext.collectedData["decision_mode"] = decisionMode.name
                stepContext.collectedData["decision_reason"] = reason
                Log.i(TAG, "Winner: $winnerName ($reason)")

                // Fire heads-up popup notification with comparison results
                onComparisonReady?.invoke(stepContext.collectedData.toMap())

                StepResult.SuccessWithData("winner_summary", "$winnerName ($reason)")
            } else {
                StepResult.Failure("Could not determine prices from either app")
            }
        }
    )
}
