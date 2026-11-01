package com.kaori.lingtouch.agent

import android.content.Context
import android.graphics.Bitmap
import com.kaori.lingtouch.accessibility.AccessibilityBridge
import com.kaori.lingtouch.accessibility.UiTree
import com.kaori.lingtouch.ocr.OcrEngine
import com.kaori.lingtouch.settings.SettingsRepository
import com.kaori.lingtouch.vision.ImageUtils
import com.kaori.lingtouch.vision.ScreenCaptureManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class Observation(
    val json: String,
    val images: List<ByteArray>,
    val tree: UiTree?
)

/**
 * Builds the multimodal observation handed to the model: foreground app, compact UI tree,
 * optional OCR text and an optional screenshot. OCR/screenshots are only produced when the
 * configuration and the current UI justify the extra cost.
 */
class ObservationBuilder(
    private val context: Context,
    private val capture: ScreenCaptureManager,
    private val ocr: OcrEngine,
    private val settings: SettingsRepository
) {
    suspend fun build(
        taskId: Long,
        goal: String,
        step: Int,
        maxSteps: Int,
        history: List<String>,
        memoryText: String,
        lastResult: JSONObject?,
        tree: UiTree?
    ): Observation {
        val config = settings.state.value
        var bitmap: Bitmap? = null
        var images: List<ByteArray> = emptyList()
        var ocrText = ""

        try {
            val wantsVision = config.sendScreenshots && config.visionModel.isNotBlank()
            val wantsOcr = config.ocrEnabled && (tree == null || tree.textNodes().size < 3)
            if (wantsVision || wantsOcr) {
                val captured = capture.capture(taskId, saveToFile = false).getOrNull()
                if (captured != null) {
                    val bmp = captured.bitmap
                    bitmap = bmp
                    if (wantsVision) {
                        val scaled = ImageUtils.downscale(bmp, 1024)
                        images = listOf(ImageUtils.toJpeg(scaled, 75))
                        if (scaled !== bmp) scaled.recycle()
                    }
                    if (wantsOcr) {
                        ocrText = runCatching {
                            withContext(Dispatchers.Default) { ocr.recognize(bmp) }
                        }.getOrNull()?.fullText.orEmpty()
                    }
                }
            }
        } finally {
            // Keep bitmap alive only while encoding/OCR runs above.
            if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
        }

        val json = JSONObject()
            .put("goal", goal)
            .put("step", step)
            .put("max_steps", if (maxSteps > 0) maxSteps else "unlimited")
            .put("foreground", JSONObject()
                .put("package", tree?.packageName?.takeIf { it.isNotBlank() } ?: AccessibilityBridge.foregroundPackage())
                .put("activity", tree?.activityName.orEmpty())
                .put("label", "")
            )
            .put("screen", JSONObject()
                .put("width", tree?.width ?: 0)
                .put("height", tree?.height ?: 0)
                .put("rotation", tree?.rotation ?: 0)
            )
            .put("ui", tree?.prompt(120) ?: "(无障碍服务未连接，无法读取界面)")
            .put("ui_text", tree?.screenText()?.take(1800).orEmpty())
            .put("history", JSONArray(history.takeLast(8)))
            .put("capabilities", JSONObject()
                .put("accessibility", AccessibilityBridge.isConnected())
                .put("screenshot", tree != null)
                .put("ocr", config.ocrEnabled)
            )
        if (ocrText.isNotBlank()) json.put("ocr", ocrText.take(1200))
        if (memoryText.isNotBlank()) json.put("memories", memoryText.take(1800))
        if (lastResult != null) json.put("last_result", lastResult)
        return Observation(json.toString(), images, tree)
    }
}
