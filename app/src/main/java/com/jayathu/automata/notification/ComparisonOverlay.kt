package com.jayathu.automata.notification

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.view.animation.DecelerateInterpolator
import android.view.animation.ScaleAnimation
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Full-screen overlay that shows price comparison results on top of any app.
 * Uses TYPE_ACCESSIBILITY_OVERLAY so no extra permissions are needed beyond
 * the accessibility service we already have.
 */
class ComparisonOverlay(
    private val service: AccessibilityService,
    private val autoDismissMs: Long = 8000L
) {

    companion object {
        private const val TAG = "ComparisonOverlay"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null

    fun show(data: Map<String, String>) {
        handler.post { showOnMainThread(data) }
    }

    fun dismiss() {
        handler.post { dismissOnMainThread() }
    }

    private fun showOnMainThread(data: Map<String, String>) {
        // Remove any existing overlay first
        dismissOnMainThread()

        val wm = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager

        val pickMePrice = data["pickme_price"]
        val uberPrice = data["uber_price"]
        val winner = data["winner"]
        val winnerSummary = data["winner_summary"]
        val pickMeEta = data["pickme_eta"]
        val uberEta = data["uber_eta"]

        val pickMe = pickMePrice?.toDoubleOrNull()
        val uber = uberPrice?.toDoubleOrNull()

        val view = buildOverlayView(
            winner = winner,
            winnerSummary = winnerSummary,
            pickMePrice = pickMePrice,
            uberPrice = uberPrice,
            pickMeEta = pickMeEta,
            uberEta = uberEta,
            pickMe = pickMe,
            uber = uber
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dpToPx(60)
        }

        try {
            wm.addView(view, params)
            overlayView = view

            // Slide-in animation
            val scaleAnim = ScaleAnimation(
                0.8f, 1f, 0.8f, 1f,
                ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
                ScaleAnimation.RELATIVE_TO_SELF, 0.5f
            )
            val fadeAnim = AlphaAnimation(0f, 1f)
            val animSet = AnimationSet(true).apply {
                addAnimation(scaleAnim)
                addAnimation(fadeAnim)
                duration = 300
                interpolator = DecelerateInterpolator()
            }
            view.startAnimation(animSet)

            // Auto-dismiss after timeout
            handler.postDelayed({ dismissWithAnimation() }, autoDismissMs)

            Log.i(TAG, "Comparison overlay shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay", e)
        }
    }

    private fun buildOverlayView(
        winner: String?,
        winnerSummary: String?,
        pickMePrice: String?,
        uberPrice: String?,
        pickMeEta: String?,
        uberEta: String?,
        pickMe: Double?,
        uber: Double?
    ): View {
        val context = service

        // Outer container with dark semi-transparent background
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))
        }

        // Card background
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(24), dpToPx(20), dpToPx(24), dpToPx(24))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(20).toFloat()
                setColor(Color.parseColor("#1A1A2E"))
                setStroke(dpToPx(2), Color.parseColor("#6C63FF"))
            }
            elevation = dpToPx(12).toFloat()
        }

        // Header title
        val titleText = winnerSummary ?: winner ?: "Comparison"
        card.addView(TextView(context).apply {
            text = "Booking $titleText"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })

        // Divider
        card.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)
            ).apply { setMargins(0, dpToPx(12), 0, dpToPx(14)) }
            setBackgroundColor(Color.parseColor("#333355"))
        })

        // Price rows
        if (pickMePrice != null) {
            val isWinner = winner == "PickMe"
            card.addView(buildPriceRow("PickMe", "Rs $pickMePrice", pickMeEta, isWinner))
        }

        if (uberPrice != null) {
            val isWinner = winner == "Uber"
            card.addView(buildPriceRow("Uber", "Rs $uberPrice", uberEta, isWinner))
        }

        // Savings line
        if (pickMe != null && uber != null) {
            val savings = kotlin.math.abs(pickMe - uber)
            card.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)
                ).apply { setMargins(0, dpToPx(12), 0, dpToPx(12)) }
                setBackgroundColor(Color.parseColor("#333355"))
            })
            card.addView(TextView(context).apply {
                text = "You save Rs ${String.format("%.0f", savings)}"
                setTextColor(Color.parseColor("#4CAF50"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
            })
        }

        // Close button
        card.addView(TextView(context).apply {
            text = "Close"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(10), 0, dpToPx(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dpToPx(14), 0, 0) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(10).toFloat()
                setColor(Color.parseColor("#6C63FF"))
            }
            setOnClickListener { dismiss() }
        })

        container.addView(card)
        return container
    }

    private fun buildPriceRow(
        appName: String,
        price: String,
        eta: String?,
        isWinner: Boolean
    ): LinearLayout {
        val context = service

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dpToPx(4), 0, dpToPx(4)) }

            if (isWinner) {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(12).toFloat()
                    setColor(Color.parseColor("#1B3A1B"))
                    setStroke(dpToPx(1), Color.parseColor("#4CAF50"))
                }
            } else {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(12).toFloat()
                    setColor(Color.parseColor("#222244"))
                }
            }
        }

        // Winner indicator
        if (isWinner) {
            row.addView(TextView(context).apply {
                text = "✓"
                setTextColor(Color.parseColor("#4CAF50"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, dpToPx(8), 0)
            })
        }

        // App name
        row.addView(TextView(context).apply {
            text = appName
            setTextColor(if (isWinner) Color.WHITE else Color.parseColor("#AAAACC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(null, if (isWinner) Typeface.BOLD else Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        // ETA
        if (eta != null) {
            row.addView(TextView(context).apply {
                text = "${eta} min"
                setTextColor(if (isWinner) Color.parseColor("#90CAF9") else Color.parseColor("#7788AA"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, 0, dpToPx(12), 0)
            })
        }

        // Price
        row.addView(TextView(context).apply {
            text = price
            setTextColor(if (isWinner) Color.parseColor("#4CAF50") else Color.parseColor("#CCCCDD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(null, Typeface.BOLD)
        })

        return row
    }

    private fun dismissWithAnimation() {
        val view = overlayView ?: return
        val fadeOut = AlphaAnimation(1f, 0f).apply {
            duration = 200
        }
        view.startAnimation(fadeOut)
        handler.postDelayed({ dismissOnMainThread() }, 200)
    }

    private fun dismissOnMainThread() {
        val view = overlayView ?: return
        try {
            val wm = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
            wm.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove overlay", e)
        }
        overlayView = null
        handler.removeCallbacksAndMessages(null)
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            service.resources.displayMetrics
        ).toInt()
    }
}
