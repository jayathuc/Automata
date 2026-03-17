package com.jayathu.automata.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object ActionExecutor {

    private const val TAG = "ActionExecutor"

    fun click(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        val clickable = NodeFinder.findClickableParent(node)
        return clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    fun setText(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scrollForward(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isScrollable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }
        var current = node.parent
        while (current != null) {
            if (current.isScrollable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            current = current.parent
        }
        return false
    }

    fun scrollBackward(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isScrollable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        }
        var current = node.parent
        while (current != null) {
            if (current.isScrollable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            }
            current = current.parent
        }
        return false
    }

    fun pressBack(service: AccessibilityService): Boolean {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    fun pressHome(service: AccessibilityService): Boolean {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    fun openRecents(service: AccessibilityService): Boolean {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
    }

    fun longClick(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        return node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
    }

    fun clearText(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    suspend fun clickAndWait(node: AccessibilityNodeInfo?, waitMs: Long = 500): Boolean {
        val result = click(node)
        if (result) delay(waitMs)
        return result
    }

    suspend fun setTextAndWait(node: AccessibilityNodeInfo?, text: String, waitMs: Long = 500): Boolean {
        val result = setText(node, text)
        if (result) delay(waitMs)
        return result
    }

    // ===== Screen metrics =====

    /**
     * Returns (width, height) in pixels for the current display.
     */
    fun getScreenSize(service: AccessibilityService): Pair<Int, Int> {
        val wm = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)
        return Pair(metrics.widthPixels, metrics.heightPixels)
    }

    fun screenCenterX(service: AccessibilityService): Float {
        return getScreenSize(service).first / 2f
    }

    // ===== Coordinate-based gestures (for Flutter/cross-platform apps) =====

    /**
     * Tap at absolute screen coordinates using dispatchGesture.
     * Works even when accessibility nodes have no text/IDs (e.g., Flutter apps).
     */
    suspend fun tapAtCoordinates(service: AccessibilityService, x: Float, y: Float, durationMs: Long = 100): Boolean {
        Log.i(TAG, "Tapping at coordinates ($x, $y) duration=${durationMs}ms")
        // Use a tiny 1px line instead of a single point to ensure proper
        // DOWN → MOVE → UP touch event sequence (needed for Flutter apps)
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        val result = dispatchGestureAndWait(service, gesture)
        Log.i(TAG, "Tap at ($x, $y) result: $result")
        return result
    }

    /**
     * Tap at percentage-based coordinates (0.0-1.0) relative to screen size.
     * This makes coordinates device-independent.
     */
    suspend fun tapAtPercent(
        service: AccessibilityService,
        xPercent: Float,
        yPercent: Float,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        val x = xPercent * screenWidth
        val y = yPercent * screenHeight
        return tapAtCoordinates(service, x, y)
    }

    /**
     * Long press at absolute screen coordinates.
     */
    suspend fun longPressAtCoordinates(service: AccessibilityService, x: Float, y: Float, durationMs: Long = 1000): Boolean {
        Log.d(TAG, "Long pressing at ($x, $y) for ${durationMs}ms")
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        return dispatchGestureAndWait(service, gesture)
    }

    /**
     * Swipe gesture from one point to another.
     */
    suspend fun swipe(
        service: AccessibilityService,
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long = 300
    ): Boolean {
        Log.d(TAG, "Swiping from ($startX,$startY) to ($endX,$endY)")
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        return dispatchGestureAndWait(service, gesture)
    }

    /**
     * Type text character by character using the clipboard + paste isn't reliable
     * for Flutter. Instead we dispatch tap on the node's bounds center, then use
     * the input connection if available.
     */
    suspend fun tapNodeCenter(service: AccessibilityService, node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return tapAtCoordinates(service, bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    private suspend fun dispatchGestureAndWait(
        service: AccessibilityService,
        gesture: GestureDescription
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Gesture completed successfully")
                if (continuation.isActive) continuation.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Gesture was CANCELLED by system")
                if (continuation.isActive) continuation.resume(false)
            }
        }

        Log.d(TAG, "Dispatching gesture with ${gesture.strokeCount} stroke(s)")
        val dispatched = service.dispatchGesture(gesture, callback, null)
        Log.d(TAG, "dispatchGesture returned: $dispatched")
        if (!dispatched) {
            Log.e(TAG, "Failed to dispatch gesture - service may not support gestures")
            if (continuation.isActive) continuation.resume(false)
        }
    }
}
