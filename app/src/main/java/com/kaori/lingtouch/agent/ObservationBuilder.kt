package com.kaori.lingtouch.agent

import android.content.Context
import android.graphics.Bitmap
import com.kaori.lingtouch.accessibility.AccessibilityBridge
import com.kaori.lingtouch.accessibility.UiTree
import com.kaori.lingtouch.api.ModelCatalog
import com.kaori.lingtouch.ocr.OcrEngine
import com.kaori.lingtouch.settings.SettingsRepository
import com.kaori.lingtouch.util.Logx
import com.kaori.lingtouch.vision.ImageUtils
import com.kaori.lingtouch.vision.ScreenCaptureManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class Observation(
    val json: String,
    val images: List<ByteArray>,
    val tree: UiTree?,
    val screen: ScreenState,
    val estimatedPromptTokens: Int,
    /** Model that must be used for this observation when an image is attached. */
    val visionModel: String = ""
)

/**
 * Builds the multimodal observation handed to the model: compact plan progress, compressed
 * context, current screen, UI tree text, OCR lines with screen coordinates, and a screenshot
 * whenever the selected (or configured vision) model can accept images.
 *
 * Screenshots are captured for CONTROL every cycle when OCR or vision is enabled, because
 * games/Unity/canvas surfaces expose no readable text and must be located visually.
 */
class ObservationBuilder(
    private val context: Context,
    private val capture: ScreenCaptureManager,
    private val ocr: OcrEngine,
    private val settings: SettingsRepository,
    private val catalog: ModelCatalog
) {
    suspend fun build(
        taskId: Long,
        goal: String,
        step: Int,
        plan: TaskPlan,
        contextSnapshot: ContextSnapshot,
        screen: ScreenState,
        lastResult: JSONObject?,
        tree: UiTree?,
        selectedModel: String = ""
    ): Observation {
        val config = settings.state.value
        val modelId = selectedModel.ifBlank { config.model }
        val selectedVision = catalog.find(modelId)?.vision == true
        val chosenVisionModel = when {
            selectedVision -> modelId
            config.visionModel.isNotBlank() -> config.visionModel
            else -> ""
        }
        val attachScreenshot = config.sendScreenshots && chosenVisionModel.isNotBlank()
        val runOcr = config.ocrEnabled
        var images: List<ByteArray> = emptyList()
        var ocrLines = JSONArray()
        var ocrText = ""
        var imageWidth = 0
        var imageHeight = 0
        var captureError = ""

        if (attachScreenshot || runOcr) {
            val captured = capture.capture(taskId, saveToFile = false).getOrElse {
                captureError = it.message ?: "截图失败"
                null
            }
            if (captured != null) {
                val bitmap = captured.bitmap
                imageWidth = bitmap.width
                imageHeight = bitmap.height
                try {
                    if (attachScreenshot) {
                        val scaled = ImageUtils.downscale(bitmap, 1280)
                        images = listOf(ImageUtils.toJpeg(scaled, 78))
                        if (scaled !== bitmap) scaled.recycle()
                    }
                    if (runOcr) {
                        val result = runCatching {
                            withContext(Dispatchers.Default) { ocr.recognize(bitmap) }
                        }.getOrNull()
                        if (result != null) {
                            val screenW = screen.width.takeIf { it > 0 } ?: bitmap.width
                            val screenH = screen.height.takeIf { it > 0 } ?: bitmap.height
                            val sx = screenW.toFloat() / bitmap.width.coerceAtLeast(1)
                            val sy = screenH.toFloat() / bitmap.height.coerceAtLeast(1)
                            result.lines.take(100).forEach { line ->
                                val left = (line.left * sx).toInt()
                                val top = (line.top * sy).toInt()
                                val right = (line.right * sx).toInt()
                                val bottom = (line.bottom * sy).toInt()
                                ocrLines.put(
                                    JSONObject()
                                        .put("text", line.text.take(120))
                                        .put("bounds", JSONArray(listOf(left, top, right, bottom)))
                                        .put("center", JSONArray(listOf((left + right) / 2, (top + bottom) / 2)))
                                )
                            }
                            ocrText = result.fullText
                        }
                    }
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }
        }

        val json = JSONObject()
            .put("goal", goal)
            .put("step", step)
            .put(
                "plan",
                JSONObject()
                    .put("progress", plan.progressText())
                    .put("current", plan.current?.title ?: "")
                    .put("revision", plan.revision)
                    .put("subgoals", plan.toJson().optJSONArray("subgoals") ?: JSONArray())
            )
            .put(
                "foreground",
                JSONObject()
                    .put("package", screen.packageName.ifBlank { AccessibilityBridge.foregroundPackage() })
                    .put("activity", screen.activityName)
            )
            .put("screen", screen.toJson())
            .put("context", contextSnapshot.text)
            .put("ui", tree?.prompt(120) ?: "(无障碍服务未连接，无法读取界面)")
            .put("ui_text", tree?.screenText()?.take(1600).orEmpty())
            .put("last_result", lastResult ?: JSONObject())
            .put(
                "screenshot",
                JSONObject()
                    .put("included", images.isNotEmpty())
                    .put("width", imageWidth)
                    .put("height", imageHeight)
                    .put("coordinate_space", "screen_pixels")
                    .put("error", captureError)
            )
            .put(
                "ocr",
                JSONObject()
                    .put("enabled", runOcr)
                    .put("image_width", imageWidth)
                    .put("image_height", imageHeight)
                    .put("screen_width", screen.width)
                    .put("screen_height", screen.height)
                    .put("lines", ocrLines)
                    .put("text", ocrText.take(1200))
                    .put(
                        "hint",
                        if (ocrLines.length() == 0 && runOcr) {
                            "OCR 未识别到文字；如果目标是图标/游戏按钮，请依据截图中的可见位置估算屏幕坐标"
                        } else ""
                    )
            )
            .put(
                "capabilities",
                JSONObject()
                    .put("accessibility", screen.accessibility || AccessibilityBridge.isConnected())
                    .put("screenshot", images.isNotEmpty())
                    .put("ocr", runOcr)
                    .put("vision_model", chosenVisionModel)
            )
        val text = json.toString()
        Logx.d("Observe", "step=$step image=${images.isNotEmpty()} ocr_lines=${ocrLines.length()} nodes=${tree?.nodes?.size ?: 0}")
        return Observation(
            json = text,
            images = images,
            tree = tree,
            screen = screen,
            estimatedPromptTokens = TokenEstimator.estimate(text) + if (images.isNotEmpty()) 900 else 0,
            visionModel = if (images.isNotEmpty()) chosenVisionModel else ""
        )
    }
}
