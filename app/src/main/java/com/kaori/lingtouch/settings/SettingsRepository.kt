package com.kaori.lingtouch.settings

import android.content.Context
import android.content.SharedPreferences
import com.kaori.lingtouch.api.ApiProtocol
import com.kaori.lingtouch.security.SecureStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for user configuration. Secrets are delegated to [SecureStore];
 * everything else lives in private SharedPreferences. All reads are cached in a StateFlow.
 */
class SettingsRepository(context: Context, private val secure: SecureStore) {
    private val prefs: SharedPreferences = context.getSharedPreferences("lt_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    private val _hasApiKey = MutableStateFlow(secure.hasApiKey())
    val hasApiKey: StateFlow<Boolean> = _hasApiKey.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        val next = normalize(transform(_state.value))
        persist(next)
        _state.value = next
        return next
    }

    fun saveApiKey(raw: String) {
        if (raw.isBlank()) return
        secure.saveApiKey(raw)
        _hasApiKey.value = secure.hasApiKey()
    }

    fun apiKey(): String? = secure.getApiKey()

    fun clearApiConfig() {
        secure.clear()
        _hasApiKey.value = false
        update {
            it.copy(
                baseUrl = AppSettings.DEFAULT_BASE_URL,
                protocol = ApiProtocol.OPENAI_CHAT,
                model = AppSettings.DEFAULT_MODEL,
                visionModel = ""
            )
        }
        prefs.edit().remove(KEY_MODELS_CACHE).apply()
    }

    fun saveModelsCache(json: String) {
        prefs.edit().putString(KEY_MODELS_CACHE, json).apply()
    }

    fun modelsCache(): String? = prefs.getString(KEY_MODELS_CACHE, null)

    fun markGuideDone() = update { it.copy(guideDone = true) }

    fun setLastTaskId(id: Long) = update { it.copy(lastTaskId = id) }

    private fun read(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            provider = prefs.getString(KEY_PROVIDER, defaults.provider) ?: defaults.provider,
            baseUrl = prefs.getString(KEY_BASE_URL, defaults.baseUrl) ?: defaults.baseUrl,
            protocol = ApiProtocol.from(prefs.getString(KEY_PROTOCOL, defaults.protocol.name)),
            model = prefs.getString(KEY_MODEL, defaults.model) ?: defaults.model,
            visionModel = prefs.getString(KEY_VISION_MODEL, defaults.visionModel) ?: defaults.visionModel,
            timeoutSeconds = prefs.getInt(KEY_TIMEOUT, defaults.timeoutSeconds),
            maxSteps = prefs.getInt(KEY_MAX_STEPS, defaults.maxSteps),
            maxRetries = prefs.getInt(KEY_MAX_RETRIES, defaults.maxRetries),
            sendScreenshots = prefs.getBoolean(KEY_SEND_SCREENSHOTS, defaults.sendScreenshots),
            ocrEnabled = prefs.getBoolean(KEY_OCR, defaults.ocrEnabled),
            autoCleanup = prefs.getBoolean(KEY_AUTO_CLEANUP, defaults.autoCleanup),
            overlayEnabled = prefs.getBoolean(KEY_OVERLAY, defaults.overlayEnabled),
            overlayX = prefs.getInt(KEY_OVERLAY_X, defaults.overlayX),
            overlayY = prefs.getInt(KEY_OVERLAY_Y, defaults.overlayY),
            guideDone = prefs.getBoolean(KEY_GUIDE_DONE, defaults.guideDone),
            lastTaskId = prefs.getLong(KEY_LAST_TASK, defaults.lastTaskId)
        )
    }

    private fun normalize(value: AppSettings): AppSettings = value.copy(
        baseUrl = value.baseUrl.trim().trimEnd('/').ifBlank { AppSettings.DEFAULT_BASE_URL },
        model = value.model.trim(),
        visionModel = value.visionModel.trim(),
        timeoutSeconds = AppSettings.clampTimeout(value.timeoutSeconds),
        maxSteps = AppSettings.clampSteps(value.maxSteps),
        maxRetries = AppSettings.clampRetries(value.maxRetries)
    )

    private fun persist(s: AppSettings) {
        prefs.edit()
            .putString(KEY_PROVIDER, s.provider)
            .putString(KEY_BASE_URL, s.baseUrl)
            .putString(KEY_PROTOCOL, s.protocol.name)
            .putString(KEY_MODEL, s.model)
            .putString(KEY_VISION_MODEL, s.visionModel)
            .putInt(KEY_TIMEOUT, s.timeoutSeconds)
            .putInt(KEY_MAX_STEPS, s.maxSteps)
            .putInt(KEY_MAX_RETRIES, s.maxRetries)
            .putBoolean(KEY_SEND_SCREENSHOTS, s.sendScreenshots)
            .putBoolean(KEY_OCR, s.ocrEnabled)
            .putBoolean(KEY_AUTO_CLEANUP, s.autoCleanup)
            .putBoolean(KEY_OVERLAY, s.overlayEnabled)
            .putInt(KEY_OVERLAY_X, s.overlayX)
            .putInt(KEY_OVERLAY_Y, s.overlayY)
            .putBoolean(KEY_GUIDE_DONE, s.guideDone)
            .putLong(KEY_LAST_TASK, s.lastTaskId)
            .apply()
    }

    private companion object {
        const val KEY_PROVIDER = "provider"
        const val KEY_BASE_URL = "base_url"
        const val KEY_PROTOCOL = "protocol"
        const val KEY_MODEL = "model"
        const val KEY_VISION_MODEL = "vision_model"
        const val KEY_TIMEOUT = "timeout"
        const val KEY_MAX_STEPS = "max_steps"
        const val KEY_MAX_RETRIES = "max_retries"
        const val KEY_SEND_SCREENSHOTS = "send_screenshots"
        const val KEY_OCR = "ocr_enabled"
        const val KEY_AUTO_CLEANUP = "auto_cleanup"
        const val KEY_OVERLAY = "overlay_enabled"
        const val KEY_OVERLAY_X = "overlay_x"
        const val KEY_OVERLAY_Y = "overlay_y"
        const val KEY_GUIDE_DONE = "guide_done"
        const val KEY_LAST_TASK = "last_task_id"
        const val KEY_MODELS_CACHE = "models_cache"
    }
}
