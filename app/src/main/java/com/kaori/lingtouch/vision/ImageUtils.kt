package com.kaori.lingtouch.vision

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File

object ImageUtils {
    fun downscale(source: Bitmap, maxDimension: Int = 1280): Bitmap {
        val max = maxOf(source.width, source.height)
        if (max <= maxDimension) return source
        val scale = maxDimension.toFloat() / max
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, w, h, true)
        return scaled
    }

    fun toJpeg(bitmap: Bitmap, quality: Int = 80): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    fun saveJpeg(bitmap: Bitmap, file: File, quality: Int = 85): Boolean = runCatching {
        file.parentFile?.mkdirs()
        file.outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        }
        file.length() > 0
    }.getOrDefault(false)

    fun crop(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Bitmap {
        val l = left.coerceIn(0, bitmap.width - 1)
        val t = top.coerceIn(0, bitmap.height - 1)
        val r = right.coerceIn(l + 1, bitmap.width)
        val b = bottom.coerceIn(t + 1, bitmap.height)
        return Bitmap.createBitmap(bitmap, l, t, r - l, b - t)
    }
}
