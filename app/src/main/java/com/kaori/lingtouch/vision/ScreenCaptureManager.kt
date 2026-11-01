package com.kaori.lingtouch.vision

import android.content.Context
import android.graphics.Bitmap
import com.kaori.lingtouch.accessibility.AccessibilityBridge
import com.kaori.lingtouch.storage.TempResourceManager
import com.kaori.lingtouch.util.Logx
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CaptureResult(
    val bitmap: Bitmap,
    val file: File?,
    val source: String
)

/**
 * Unified screenshot pipeline: accessibility screenshot first, MediaProjection fallback.
 * Every capture is written only to the task's private cache directory.
 */
class ScreenCaptureManager(
    private val context: Context,
    private val temp: TempResourceManager
) {
    fun accessibilityAvailable(): Boolean = AccessibilityBridge.isConnected()

    fun projectionAvailable(): Boolean = ScreenCaptureService.isReady

    suspend fun capture(taskId: Long, saveToFile: Boolean = true): Result<CaptureResult> {
        AccessibilityBridge.service()?.let { service ->
            val bitmap = runCatching { service.screenshot() }.getOrNull()
            if (bitmap != null) {
                return Result.success(persist(taskId, bitmap, "accessibility", saveToFile))
            }
        }
        val projected = runCatching { ScreenCaptureService.capture(context) }.getOrNull()
        if (projected != null) {
            return Result.success(persist(taskId, projected, "mediaprojection", saveToFile))
        }
        val reason = when {
            !AccessibilityBridge.isConnected() && !ScreenCaptureService.isReady ->
                "无障碍服务未启用，且未授权屏幕录制"
            !ScreenCaptureService.isReady -> "无障碍截图失败，且未授权屏幕录制"
            else -> "无障碍截图与屏幕录制均不可用（可能是安全窗口）"
        }
        Logx.w("Capture", reason)
        return Result.failure(IllegalStateException(reason))
    }

    private suspend fun persist(taskId: Long, bitmap: Bitmap, source: String, save: Boolean): CaptureResult =
        withContext(Dispatchers.IO) {
            val file = if (save) temp.file(taskId, "screen", "jpg") else null
            if (file != null) ImageUtils.saveJpeg(bitmap, file)
            CaptureResult(bitmap, file, source)
        }
}
