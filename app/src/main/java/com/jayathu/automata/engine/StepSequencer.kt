package com.jayathu.automata.engine

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

class StepSequencer(
    private val rootProvider: () -> AccessibilityNodeInfo?,
    private val onBackOut: suspend () -> Unit
) {
    companion object {
        private const val TAG = "StepSequencer"
        private const val UI_POLL_INTERVAL_MS = 200L
    }

    private val _state = MutableStateFlow<AutomationState>(AutomationState.Idle)
    val state: StateFlow<AutomationState> = _state.asStateFlow()

    @Volatile
    private var abortRequested = false

    suspend fun execute(steps: List<AutomationStep>): AutomationResult {
        abortRequested = false
        val context = StepContext(
            stepIndex = 0,
            totalSteps = steps.size
        )

        Log.i(TAG, "Starting automation with ${steps.size} steps")

        for ((index, step) in steps.withIndex()) {
            if (abortRequested) {
                Log.w(TAG, "Abort requested before step ${step.name}")
                _state.value = AutomationState.Aborted
                return AutomationResult.Aborted
            }

            context.stepIndex = index
            _state.value = AutomationState.Running(step.name, index, steps.size)
            Log.i(TAG, "Step ${index + 1}/${steps.size}: ${step.name}")

            if (step.delayBeforeMs > 0) {
                delay(step.delayBeforeMs)
            }

            val stepResult = executeStep(step, context)

            when (stepResult) {
                is StepOutcome.Success -> {
                    _state.value = AutomationState.StepComplete(step.name, index, steps.size)
                    Log.i(TAG, "Step ${step.name} completed successfully")
                    if (step.delayAfterMs > 0) {
                        delay(step.delayAfterMs)
                    }
                }
                is StepOutcome.Skipped -> {
                    Log.i(TAG, "Step ${step.name} skipped: ${stepResult.reason}")
                    _state.value = AutomationState.StepComplete(step.name, index, steps.size)
                }
                is StepOutcome.Failed -> {
                    Log.e(TAG, "Step ${step.name} failed: ${stepResult.reason}")
                    _state.value = AutomationState.Error(step.name, stepResult.reason, false)
                    attemptBackOut()
                    return AutomationResult.Failed(step.name, stepResult.reason, context.collectedData)
                }
                is StepOutcome.TimedOut -> {
                    Log.e(TAG, "Step ${step.name} timed out")
                    _state.value = AutomationState.Error(step.name, "Timed out waiting for UI", true)
                    attemptBackOut()
                    return AutomationResult.Failed(step.name, "Timed out after ${step.timeoutMs}ms", context.collectedData)
                }
            }
        }

        _state.value = AutomationState.Done(context.collectedData.toMap())
        SecureLog.verbose(TAG, "Automation completed. Data: ${context.collectedData}")
        return AutomationResult.Success(context.collectedData.toMap())
    }

    private suspend fun executeStep(step: AutomationStep, context: StepContext): StepOutcome {
        var retries = 0

        while (retries <= step.maxRetries) {
            if (abortRequested) return StepOutcome.Failed("Aborted")

            // Wait for the UI condition to be met
            val root = waitForCondition(step) ?: return StepOutcome.TimedOut

            // Execute the action
            try {
                when (val result = step.action(root, context)) {
                    is StepResult.Success -> return StepOutcome.Success
                    is StepResult.SuccessWithData -> {
                        context.collectedData[result.key] = result.value
                        return StepOutcome.Success
                    }
                    is StepResult.Skip -> return StepOutcome.Skipped(result.reason)
                    is StepResult.Retry -> {
                        retries++
                        Log.w(TAG, "Step ${step.name} retry $retries/${step.maxRetries}: ${result.reason}")
                        delay(500)
                    }
                    is StepResult.Failure -> return StepOutcome.Failed(result.reason)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Step ${step.name} threw exception", e)
                retries++
                if (retries > step.maxRetries) {
                    return StepOutcome.Failed("Exception: ${e.message}")
                }
                delay(500)
            }
        }

        return StepOutcome.Failed("Max retries (${step.maxRetries}) exceeded")
    }

    private suspend fun waitForCondition(step: AutomationStep): AccessibilityNodeInfo? {
        val startTime = System.currentTimeMillis()

        while (true) {
            if (abortRequested) return null

            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed >= step.timeoutMs) return null

            _state.value = AutomationState.WaitingForUI(step.name, elapsed, step.timeoutMs)

            val root = rootProvider()
            if (root != null && step.waitCondition(root)) {
                return root
            }

            delay(UI_POLL_INTERVAL_MS)
        }
    }

    private suspend fun attemptBackOut() {
        Log.w(TAG, "Attempting back-out recovery")
        try {
            onBackOut()
        } catch (e: Exception) {
            Log.e(TAG, "Back-out failed", e)
        }
    }

    fun abort() {
        abortRequested = true
        Log.w(TAG, "Abort requested")
    }

    fun reset() {
        abortRequested = false
        _state.value = AutomationState.Idle
    }
}

private sealed class StepOutcome {
    data object Success : StepOutcome()
    data class Skipped(val reason: String) : StepOutcome()
    data class Failed(val reason: String) : StepOutcome()
    data object TimedOut : StepOutcome()
}

sealed class AutomationResult {
    data class Success(val data: Map<String, String>) : AutomationResult()
    data class Failed(val stepName: String, val reason: String, val partialData: Map<String, String>) : AutomationResult()
    data object Aborted : AutomationResult()
}
