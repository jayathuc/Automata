package com.jayathu.automata.engine

import android.util.Log
import com.jayathu.automata.BuildConfig

/**
 * Logging utility that redacts sensitive data (addresses, prices, OCR text)
 * in release builds. In debug builds, full data is logged for development.
 */
object SecureLog {

    private val PRICE_PATTERN = Regex("""(?:Rs\.?|LKR)\s*[\d,\.]+""", RegexOption.IGNORE_CASE)
    private val PLUSCODE_PATTERN = Regex("""\b[23456789CFGHJMPQRVWX]{4,8}\+[23456789CFGHJMPQRVWX]{2,3}\b""")

    fun i(tag: String, msg: String) {
        Log.i(tag, sanitize(msg))
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, sanitize(msg))
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, sanitize(msg))
    }

    /** Log with full detail — only in debug builds, completely suppressed in release. */
    fun verbose(tag: String, msg: String) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, msg)
        }
    }

    private fun sanitize(msg: String): String {
        if (BuildConfig.DEBUG) return msg
        return msg
            .replace(PRICE_PATTERN, "Rs ***")
            .replace(PLUSCODE_PATTERN, "***+***")
    }
}
