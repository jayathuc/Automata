package com.jayathu.automata.engine

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class OcrTextBlock(
    val text: String,
    val bounds: Rect?
)

data class OcrResult(
    val fullText: String,
    val blocks: List<OcrTextBlock>
)

object ScreenReader {

    private const val TAG = "ScreenReader"

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Takes a screenshot using AccessibilityService.takeScreenshot() (API 30+)
     * and returns the bitmap.
     */
    suspend fun takeScreenshot(service: AccessibilityService): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.e(TAG, "takeScreenshot requires API 30+, current: ${Build.VERSION.SDK_INT}")
            return null
        }

        return suspendCancellableCoroutine { continuation ->
            service.takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val hardwareBuffer = result.hardwareBuffer
                        val colorSpace = result.colorSpace
                        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                        hardwareBuffer.close()

                        if (bitmap != null) {
                            // Convert to software bitmap for ML Kit processing
                            val softBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                            bitmap.recycle()
                            if (continuation.isActive) continuation.resume(softBitmap)
                        } else {
                            Log.e(TAG, "Failed to create bitmap from screenshot")
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "Screenshot failed with error code: $errorCode")
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            )
        }
    }

    /**
     * Run OCR on a bitmap and return all recognized text with bounds.
     */
    suspend fun recognizeText(bitmap: Bitmap): OcrResult? {
        val image = InputImage.fromBitmap(bitmap, 0)

        return suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val blocks = visionText.textBlocks.flatMap { block ->
                        block.lines.map { line ->
                            OcrTextBlock(
                                text = line.text,
                                bounds = line.boundingBox
                            )
                        }
                    }
                    Log.d(TAG, "OCR found ${blocks.size} text lines: ${visionText.text.take(200)}")
                    if (continuation.isActive) {
                        continuation.resume(OcrResult(visionText.text, blocks))
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "OCR failed", e)
                    if (continuation.isActive) continuation.resume(null)
                }
        }
    }

    /**
     * Take screenshot + run OCR in one call. Convenience method.
     */
    suspend fun captureAndRead(service: AccessibilityService): OcrResult? {
        val bitmap = takeScreenshot(service) ?: return null
        return try {
            recognizeText(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Take screenshot + run OCR, but only on a cropped region of the screen.
     * Useful for reading prices from a specific area (e.g., bottom panel).
     */
    suspend fun captureAndReadRegion(
        service: AccessibilityService,
        region: Rect
    ): OcrResult? {
        val fullBitmap = takeScreenshot(service) ?: return null
        return try {
            // Clamp region to bitmap bounds
            val left = region.left.coerceIn(0, fullBitmap.width)
            val top = region.top.coerceIn(0, fullBitmap.height)
            val right = region.right.coerceIn(left, fullBitmap.width)
            val bottom = region.bottom.coerceIn(top, fullBitmap.height)

            if (right - left <= 0 || bottom - top <= 0) {
                Log.e(TAG, "Invalid crop region after clamping: [$left,$top-$right,$bottom]")
                return null
            }

            val cropped = Bitmap.createBitmap(fullBitmap, left, top, right - left, bottom - top)
            try {
                recognizeText(cropped)
            } finally {
                cropped.recycle()
            }
        } finally {
            fullBitmap.recycle()
        }
    }

    /**
     * Search OCR results for a price matching a pattern like "Rs 150" or "LKR 1,500.00"
     */
    fun extractPrice(ocrResult: OcrResult, prefix: Regex = Regex("""(?:Rs\.?|LKR)\s*""")): String? {
        val pricePattern = Regex("""(?:Rs\.?|LKR)\s*(\d[\d,.]*)""", RegexOption.IGNORE_CASE)

        for (block in ocrResult.blocks) {
            val match = pricePattern.find(block.text)
            if (match != null) {
                val price = sanitizePrice(match.groupValues[1])
                Log.i(TAG, "Price found: $price from raw '${match.groupValues[1]}' in text '${block.text}'")
                return price
            }
        }

        // Also try the full text in case line splitting broke the pattern
        val fullMatch = pricePattern.find(ocrResult.fullText)
        if (fullMatch != null) {
            val price = sanitizePrice(fullMatch.groupValues[1])
            Log.i(TAG, "Price found in full text: $price from raw '${fullMatch.groupValues[1]}'")
            return price
        }

        Log.w(TAG, "No price found in OCR text: ${ocrResult.fullText.take(300)}")
        return null
    }

    /**
     * Sanitize an OCR-read price string.
     *
     * OCR commonly misreads commas as periods, producing strings like "7.733.67"
     * instead of "7,733.67". This function detects and fixes such errors:
     *
     * - Multiple dots: treat all but the last as thousands separators (remove them)
     *   e.g. "7.733.67" → "7733.67", "1.200.50" → "1200.50"
     * - Mixed commas and dots: remove commas (thousands separators), keep last dot
     *   e.g. "7,733.67" → "7733.67"
     * - Only commas: remove them (e.g. "7,733" → "7733")
     * - Single dot: leave as-is (e.g. "733.67" → "733.67")
     *
     * Sri Lankan ride prices are typically 100–99,999 LKR.
     */
    fun sanitizePrice(raw: String): String {
        // Strip any spaces
        var s = raw.trim()

        // Remove commas — they're always thousands separators in LKR
        s = s.replace(",", "")

        // Count remaining dots
        val dotCount = s.count { it == '.' }

        if (dotCount <= 1) {
            // Normal case: "7733.67" or "7733"
            return s
        }

        // Multiple dots: OCR likely misread commas as dots.
        // Keep only the LAST dot as the decimal separator, remove the rest.
        val lastDotIndex = s.lastIndexOf('.')
        val beforeLastDot = s.substring(0, lastDotIndex).replace(".", "")
        val afterLastDot = s.substring(lastDotIndex) // includes the dot
        val fixed = beforeLastDot + afterLastDot
        Log.i(TAG, "sanitizePrice: '$raw' → '$fixed' (fixed ${dotCount - 1} misread comma(s))")
        return fixed
    }

    /**
     * Find text blocks that contain a given substring (case-insensitive).
     */
    fun findTextBlocks(ocrResult: OcrResult, query: String): List<OcrTextBlock> {
        return ocrResult.blocks.filter {
            it.text.contains(query, ignoreCase = true)
        }
    }
}
