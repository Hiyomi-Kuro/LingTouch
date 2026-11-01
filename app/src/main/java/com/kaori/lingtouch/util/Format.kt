package com.kaori.lingtouch.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object Format {
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    fun time(ms: Long): String = if (ms <= 0) "-" else timeFmt.format(Date(ms))

    fun duration(ms: Long): String = when {
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> String.format(Locale.US, "%.1fs", ms / 1000.0)
        else -> {
            val m = TimeUnit.MILLISECONDS.toMinutes(ms)
            val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
            "${m}m${s}s"
        }
    }

    fun bytes(size: Long): String = when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", size / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", size / (1024.0 * 1024.0))
    }

    fun truncate(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max) + "…"
}
