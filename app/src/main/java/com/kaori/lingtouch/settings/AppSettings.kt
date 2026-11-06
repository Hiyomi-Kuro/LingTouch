package com.kaori.lingtouch.settings

import com.kaori.lingtouch.api.ApiProtocol

data class AppSettings(
    val provider: String = PROVIDER_OPENCODE_GO,
    val baseUrl: String = DEFAULT_BASE_URL,
    val protocol: ApiProtocol = ApiProtocol.OPENAI_CHAT,
    val model: String = DEFAULT_MODEL,
    val visionModel: String = "",
    val timeoutSeconds: Int = 120,
    val toolTimeoutSeconds: Int = 45,
    val maxSteps: Int = 0, // legacy field; execution is no longer capped
    val maxRetries: Int = 2,
    val sendScreenshots: Boolean = true,
    val ocrEnabled: Boolean = true,
    val autoCleanup: Boolean = true,
    val overlayEnabled: Boolean = true,
    val overlayX: Int = -1,
    val overlayY: Int = -1,
    val guideDone: Boolean = false,
    val workUri: String = "",
    val workName: String = "",
    val lastMode: String = "CHAT",
    val lastTaskId: Long = -1L
) {
    val hasVision: Boolean get() = visionModel.isNotBlank()

    companion object {
        const val PROVIDER_OPENCODE_GO = "OpenCode Go"
        const val PROVIDER_CUSTOM = "Custom (OpenAI compatible)"
        const val DEFAULT_BASE_URL = "https://opencode.ai/inference/go"
        const val DEFAULT_MODEL = "glm-5.3-flash"

        val PRESETS = listOf(
            PROVIDER_OPENCODE_GO to DEFAULT_BASE_URL,
            PROVIDER_CUSTOM to "https://api.openai.com"
        )

        fun clampSteps(value: Int): Int = value.coerceIn(0, 500)
        fun clampRetries(value: Int): Int = value.coerceIn(0, 6)
        fun clampTimeout(value: Int): Int = value.coerceIn(15, 600)
        fun clampToolTimeout(value: Int): Int = value.coerceIn(5, 180)
    }
}
