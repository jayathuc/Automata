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

        // Phase 0: Force-close both apps so they start from a clean state
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
                UberScript.buildQuickBookingSteps(context, config.rideType, config.destinationAddress)
            ))
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
     * Force-close PickMe and Uber so they start fresh from their home screens.
     */
    private fun forceCloseApps(config: TaskConfig) = AutomationStep(
        name = "Force-close apps",
        waitCondition = { true },
        timeoutMs = 5_000,
        delayAfterMs = 500,
        action = { _, _ ->
            if (config.enablePickMe) {
                AutomationEngine.forceCloseApp(context, RideApp.PICKME.packageName)
            }
            if (config.enableUber) {
                AutomationEngine.forceCloseApp(context, RideApp.UBER.packageName)
            }
            Log.i(TAG, "Force-closed enabled apps, ready for fresh start")
            StepResult.Success
        }
    )

    private fun returnToHome() = AutomationStep(
        name = "Return to home screen",
        waitCondition = { true },
        timeoutMs = 3_000,
        delayAfterMs = 1000,
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

            val pickMePrice = pickMeRaw?.toDoubleOrNull()
            val uberPrice = uberRaw?.toDoubleOrNull()
            val pickMeEta = pickMeEtaRaw?.toIntOrNull()
            val uberEta = uberEtaRaw?.toIntOrNull()

            Log.i(TAG, "Price comparison - PickMe: raw='$pickMeRaw' parsed=$pickMePrice, Uber: raw='$uberRaw' parsed=$uberPrice")
            Log.i(TAG, "ETA comparison - PickMe: ${pickMeEta ?: "N/A"} min, Uber: ${uberEta ?: "N/A"} min")
            Log.i(TAG, "Decision mode: $decisionMode")

            val winner = when {
                pickMePrice != null && uberPrice != null -> {
                    when (decisionMode) {
                        DecisionMode.CHEAPEST -> {
                            val w = if (pickMePrice < uberPrice) RideApp.PICKME
                                    else if (uberPrice < pickMePrice) RideApp.UBER
                                    else RideApp.PICKME // tie → PickMe
                            Log.i(TAG, "CHEAPEST: PickMe=$pickMePrice vs Uber=$uberPrice → winner=${w.displayName}")
                            w
                        }
                        DecisionMode.FASTEST -> {
                            val w = when {
                                pickMeEta != null && uberEta != null -> {
                                    if (pickMeEta <= uberEta) RideApp.PICKME else RideApp.UBER
                                }
                                pickMeEta != null -> RideApp.PICKME
                                uberEta != null -> RideApp.UBER
                                // No ETA from either app — fall back to cheapest
                                else -> {
                                    Log.i(TAG, "No ETA available, falling back to cheapest")
                                    if (pickMePrice <= uberPrice) RideApp.PICKME else RideApp.UBER
                                }
                            }
                            Log.i(TAG, "FASTEST: PickMe ETA=$pickMeEta vs Uber ETA=$uberEta → winner=${w.displayName}")
                            w
                        }
                    }
                }
                pickMePrice != null -> {
                    Log.i(TAG, "Only PickMe price available ($pickMePrice), Uber price missing")
                    RideApp.PICKME
                }
                uberPrice != null -> {
                    Log.i(TAG, "Only Uber price available ($uberPrice), PickMe price missing")
                    RideApp.UBER
                }
                else -> null
            }

            _result.value = OrchestratorResult(
                pickMePrice = pickMePrice,
                uberPrice = uberPrice,
                pickMeEta = pickMeEta,
                uberEta = uberEta,
                winner = winner
            )

            if (winner != null) {
                val winnerName = winner.displayName
                val detail = when (decisionMode) {
                    DecisionMode.CHEAPEST -> {
                        if (pickMePrice != null && uberPrice != null) {
                            val diff = kotlin.math.abs(pickMePrice - uberPrice)
                            " (save Rs ${String.format("%.0f", diff)})"
                        } else ""
                    }
                    DecisionMode.FASTEST -> {
                        if (pickMeEta != null && uberEta != null) {
                            val diff = kotlin.math.abs(pickMeEta - uberEta)
                            " (${diff} min faster)"
                        } else ""
                    }
                }

                stepContext.collectedData["winner"] = winnerName
                stepContext.collectedData["winner_summary"] = "$winnerName$detail"
                Log.i(TAG, "Winner stored: collectedData[winner]='${stepContext.collectedData["winner"]}', summary='$winnerName$detail' — booking now")

                // Fire heads-up popup notification with comparison results
                onComparisonReady?.invoke(stepContext.collectedData.toMap())

                StepResult.SuccessWithData("winner_summary", "$winnerName$detail")
            } else {
                StepResult.Failure("Could not determine prices from either app")
            }
        }
    )
}
