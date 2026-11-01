package com.kaori.lingtouch.util

import android.util.Log

/** Structured, bounded and privacy-aware logging. */
object Logx {
    private const val TAG = "LingTouch"
    private const val MAX_CHARS = 2600

    fun d(scope: String, message: String) = Log.d(TAG, format(scope, message))

    fun i(scope: String, message: String) = Log.i(TAG, format(scope, message))

    fun w(scope: String, message: String, tr: Throwable? = null) =
        Log.w(TAG, format(scope, message), tr)

    fun e(scope: String, message: String, tr: Throwable? = null) =
        Log.e(TAG, format(scope, message), tr)

    private fun format(scope: String, message: String): String {
        val clean = redact(message)
        val body = if (clean.length > MAX_CHARS) clean.take(MAX_CHARS) + "…(${clean.length} chars)" else clean
        return "[$scope] $body"
    }

    /** Removes API keys / bearer tokens / long secrets from any log line. */
    fun redact(input: String): String {
        var s = input
        s = Regex("(?i)bearer\\s+[A-Za-z0-9._\\-]{6,}").replace(s, "Bearer ***")
        s = Regex("(?i)sk-[A-Za-z0-9._\\-]{6,}").replace(s, "sk-***")
        s = Regex("(?i)(\"?(api[_-]?key|authorization|x-api-key)\"?\\s*[:=]\\s*\"?)[^\",}\\s]{6,}").replace(s, "$1***")
        return s
    }
}
