package com.kaori.lingtouch.ocr

import android.graphics.Bitmap

data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

data class OcrResult(
    val lines: List<OcrLine>,
    val fullText: String,
    val width: Int,
    val height: Int
) {
    fun find(query: String, contains: Boolean = true): OcrLine? =
        lines.firstOrNull {
            if (contains) it.text.contains(query, ignoreCase = true) else it.text.equals(query, ignoreCase = true)
        }

    fun compact(): String = lines.take(120).joinToString("\n") {
        "\"${it.text.take(60)}\" b=[${it.left},${it.top},${it.right},${it.bottom}]"
    }
}

interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap): OcrResult
    fun close()
}
