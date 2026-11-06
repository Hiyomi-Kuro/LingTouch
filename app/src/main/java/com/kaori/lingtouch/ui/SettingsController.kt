package com.kaori.lingtouch.ui

import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kaori.lingtouch.BuildConfig
import com.kaori.lingtouch.LingTouchApp
import com.kaori.lingtouch.R
import com.kaori.lingtouch.accessibility.AccessibilityBridge
import com.kaori.lingtouch.api.ApiOutcome
import com.kaori.lingtouch.api.ApiProtocol
import com.kaori.lingtouch.api.ModelInfo
import com.kaori.lingtouch.databinding.ViewSettingsBinding
import com.kaori.lingtouch.overlay.OverlayService
import com.kaori.lingtouch.settings.AppSettings
import com.kaori.lingtouch.ui.adapter.ModelAdapter
import com.kaori.lingtouch.util.Format
import com.kaori.lingtouch.vision.ScreenCaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsController(
    private val activity: MainActivity,
    private val binding: ViewSettingsBinding
) {
    private val app get() = LingTouchApp.get(activity)
    private var populating = false
    private val protocolValues = listOf(
        ApiProtocol.OPENAI_CHAT, ApiProtocol.OPENAI_RESPONSES, ApiProtocol.ANTHROPIC
    )

    init {
        setupDropdowns()
        binding.saveApiButton.setOnClickListener { persistFields(showToast = true) }
        binding.loadModelsButton.setOnClickListener { loadModels() }
        binding.testApiButton.setOnClickListener { testConnection() }
        binding.clearApiButton.setOnClickListener { confirmClearApi() }
        binding.loadModelsButton.setOnClickListener { loadModels() }

        binding.switchScreenshots.setOnCheckedChangeListener { _, checked ->
            if (!populating) app.settings.update { it.copy(sendScreenshots = checked) }
        }
        binding.switchOcr.setOnCheckedChangeListener { _, checked ->
            if (!populating) app.settings.update { it.copy(ocrEnabled = checked) }
        }
        binding.switchCleanup.setOnCheckedChangeListener { _, checked ->
            if (!populating) app.settings.update { it.copy(autoCleanup = checked) }
        }

        binding.accessibilityButton.setOnClickListener { AccessibilityBridge.openSettings(activity) }
        binding.overlayButton.setOnClickListener { toggleOverlay() }
        binding.notificationButton.setOnClickListener { activity.requestNotificationPermission() }
        binding.batteryButton.setOnClickListener { requestBatteryExemption() }
        binding.screenshotButton.setOnClickListener { activity.requestScreenCapture() }
        binding.shizukuRequestButton.setOnClickListener {
            val message = app.shizuku.requestPermission()
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            app.shizuku.refresh()
        }
        binding.shizukuGuideButton.setOnClickListener { showShizukuGuide() }

        binding.cleanCacheButton.setOnClickListener { cleanCache() }
        binding.clearTasksButton.setOnClickListener { confirmClearTasks() }
        binding.clearMemoriesButton.setOnClickListener { confirmClearMemories() }

        binding.versionText.text = activity.getString(R.string.settings_version, BuildConfig.VERSION_NAME)

        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.shizuku.state.collect { renderShizuku() } }
                launch { AccessibilityBridge.connected.collect { renderPermissions() } }
            }
        }
        refresh()
    }

    fun onShown() {
        refresh()
        renderPermissions()
        renderShizuku()
    }

    fun refresh() {
        val s = app.settings.state.value
        populating = true
        binding.providerInput.setText(s.provider, false)
        binding.baseUrlInput.setText(s.baseUrl)
        binding.apiKeyInput.setText("")
        binding.apiKeyLayout.helperText = if (app.settings.hasApiKey.value) {
            activity.getString(R.string.settings_api_key_saved)
        } else null
        binding.protocolInput.setText(protocolLabel(s.protocol), false)
        binding.modelInput.setText(s.model, false)
        binding.visionModelInput.setText(s.visionModel)
        binding.timeoutInput.setText(s.timeoutSeconds.toString())
        binding.retriesInput.setText(s.maxRetries.toString())
        binding.toolTimeoutInput.setText(s.toolTimeoutSeconds.toString())
        binding.switchScreenshots.isChecked = s.sendScreenshots
        binding.switchOcr.isChecked = s.ocrEnabled
        binding.switchCleanup.isChecked = s.autoCleanup
        binding.modelsStatus.text = if (app.catalog.models.value.isEmpty()) "" else
            activity.getString(R.string.settings_models_loaded, app.catalog.models.value.size)
        populating = false
        renderPermissions()
    }

    private fun setupDropdowns() {
        binding.providerInput.setSimpleItems(AppSettings.PRESETS.map { it.first }.toTypedArray())
        binding.providerInput.setOnItemClickListener { _, _, position, _ ->
            val preset = AppSettings.PRESETS.getOrNull(position) ?: return@setOnItemClickListener
            binding.providerInput.setText(preset.first, false)
            binding.baseUrlInput.setText(preset.second)
        }
        binding.protocolInput.setSimpleItems(protocolLabels().toTypedArray())
        binding.protocolInput.setOnItemClickListener { _, _, position, _ ->
            binding.protocolInput.setText(protocolLabels()[position], false)
        }
        updateModelSuggestions()
    }

    private fun protocolLabels(): List<String> = listOf(
        activity.getString(R.string.settings_protocol_openai),
        activity.getString(R.string.settings_protocol_responses),
        activity.getString(R.string.settings_protocol_anthropic)
    )

    private fun protocolLabel(protocol: ApiProtocol): String {
        val index = protocolValues.indexOf(protocol)
        return protocolLabels().getOrElse(if (index >= 0) index else 0) { protocolLabels().first() }
    }

    private fun protocolFrom(label: String): ApiProtocol {
        val index = protocolLabels().indexOf(label)
        return protocolValues.getOrElse(if (index >= 0) index else 0) { ApiProtocol.OPENAI_CHAT }
    }

    private fun text(edit: android.widget.EditText): String = edit.text?.toString()?.trim().orEmpty()

    private fun persistFields(showToast: Boolean): Boolean {
        val baseUrl = text(binding.baseUrlInput)
        if (!baseUrl.startsWith("http")) {
            binding.baseUrlInput.error = "Base URL 必须以 http:// 或 https:// 开头"
            return false
        }
        val model = text(binding.modelInput)
        if (model.isBlank()) {
            binding.modelInput.error = activity.getString(R.string.error_no_model)
            return false
        }
        val timeout = text(binding.timeoutInput).toIntOrNull() ?: 120
        val retries = text(binding.retriesInput).toIntOrNull() ?: 2
        val toolTimeout = text(binding.toolTimeoutInput).toIntOrNull() ?: 45
        val key = text(binding.apiKeyInput)

        app.settings.update {
            it.copy(
                provider = text(binding.providerInput).ifBlank { AppSettings.PROVIDER_CUSTOM },
                baseUrl = baseUrl,
                protocol = protocolFrom(text(binding.protocolInput)),
                model = model,
                visionModel = text(binding.visionModelInput),
                timeoutSeconds = timeout,
                maxRetries = retries,
                toolTimeoutSeconds = toolTimeout,
                sendScreenshots = binding.switchScreenshots.isChecked,
                ocrEnabled = binding.switchOcr.isChecked,
                autoCleanup = binding.switchCleanup.isChecked
            )
        }
        if (key.isNotBlank()) {
            app.settings.saveApiKey(key)
            binding.apiKeyInput.setText("")
        }
        binding.apiKeyLayout.helperText = if (app.settings.hasApiKey.value) {
            activity.getString(R.string.settings_api_key_saved)
        } else null
        if (showToast) Toast.makeText(activity, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        return true
    }

    private fun loadModels() {
        if (!persistFields(showToast = false)) return
        activity.lifecycleScope.launch {
            binding.loadModelsButton.isEnabled = false
            binding.modelsStatus.text = activity.getString(R.string.settings_loading_models)
            when (val outcome = app.catalog.refresh()) {
                is ApiOutcome.Ok -> {
                    updateModelSuggestions()
                    binding.modelsStatus.text = activity.getString(R.string.settings_models_loaded, outcome.value.size)
                    showModelPicker()
                }
                is ApiOutcome.Err -> {
                    binding.modelsStatus.text = activity.getString(R.string.settings_models_failed, outcome.error.message)
                }
            }
            binding.loadModelsButton.isEnabled = true
        }
    }

    private fun updateModelSuggestions() {
        val ids = app.catalog.models.value.map { it.id }
        if (ids.isNotEmpty()) binding.modelInput.setSimpleItems(ids.toTypedArray())
    }

    private fun showModelPicker() {
        val models = app.catalog.models.value
        if (models.isEmpty()) return
        val adapter = ModelAdapter { showModelChoice(it) }
        val recycler = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            this.adapter = adapter
            setPadding(32, 12, 32, 12)
        }
        adapter.submitList(models)
        val container = FrameLayout(activity).apply {
            addView(recycler, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(420)))
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.settings_load_models)
            .setView(container)
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun showModelChoice(model: ModelInfo) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(model.id)
            .setItems(arrayOf("设为默认模型", "设为视觉模型")) { _, which ->
                if (which == 0) {
                    binding.modelInput.setText(model.id, false)
                    app.settings.update { it.copy(model = model.id) }
                } else {
                    binding.visionModelInput.setText(model.id)
                    binding.switchScreenshots.isChecked = true
                    app.settings.update { it.copy(visionModel = model.id, sendScreenshots = true) }
                }
                Toast.makeText(activity, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun testConnection() {
        if (!persistFields(showToast = false)) return
        activity.lifecycleScope.launch {
            binding.testApiButton.isEnabled = false
            binding.modelsStatus.text = activity.getString(R.string.settings_testing)
            when (val outcome = app.api.testConnection()) {
                is ApiOutcome.Ok -> binding.modelsStatus.text = activity.getString(
                    R.string.settings_test_ok,
                    "${outcome.value.model} · ${outcome.value.latencyMs}ms · ${outcome.value.text.take(40)}"
                )
                is ApiOutcome.Err -> binding.modelsStatus.text =
                    activity.getString(R.string.settings_test_fail, outcome.error.message)
            }
            binding.testApiButton.isEnabled = true
        }
    }

    private fun confirmClearApi() {
        MaterialAlertDialogBuilder(activity)
            .setMessage(R.string.settings_clear_config_confirm)
            .setPositiveButton(R.string.action_clear) { _, _ ->
                app.settings.clearApiConfig()
                refresh()
                Toast.makeText(activity, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    fun renderPermissions() {
        val a11y = AccessibilityBridge.isConnected()
        val a11yEnabled = AccessibilityBridge.isEnabledInSettings(activity)
        binding.accessibilityStatusText.text = activity.getString(R.string.settings_accessibility) + "：" +
            when {
                a11y -> activity.getString(R.string.perm_granted)
                a11yEnabled -> "已开启，等待连接"
                else -> activity.getString(R.string.perm_denied)
            }

        val overlay = Settings.canDrawOverlays(activity)
        binding.overlayStatusText.text = activity.getString(R.string.settings_overlay) + "：" +
            if (overlay) activity.getString(R.string.perm_granted) else activity.getString(R.string.perm_denied)
        binding.overlayButton.text = if (overlay) {
            activity.getString(R.string.action_open)
        } else activity.getString(R.string.action_grant)

        val notifications = NotificationManagerCompat.from(activity).areNotificationsEnabled()
        binding.notificationStatusText.text = activity.getString(R.string.settings_notification) + "：" +
            if (notifications) activity.getString(R.string.perm_granted) else activity.getString(R.string.perm_denied)

        val captureReady = ScreenCaptureService.isReady
        binding.screenshotStatusText.text = activity.getString(R.string.settings_screenshot) + "：" +
            if (captureReady) activity.getString(R.string.perm_granted) else activity.getString(R.string.perm_denied)
        binding.screenshotButton.text = if (captureReady) {
            activity.getString(R.string.action_refresh)
        } else activity.getString(R.string.action_grant)

        val powerManager = activity.getSystemService(android.os.PowerManager::class.java)
        val unrestricted = powerManager?.isIgnoringBatteryOptimizations(activity.packageName) == true
        binding.batteryStatusText.text = activity.getString(R.string.settings_battery) + "：" +
            if (unrestricted) activity.getString(R.string.perm_granted) else activity.getString(R.string.perm_denied)
        binding.batteryButton.text = if (unrestricted) {
            activity.getString(R.string.perm_granted)
        } else {
            activity.getString(R.string.action_allow)
        }
        binding.batteryButton.isEnabled = !unrestricted
    }

    private fun requestBatteryExemption() {
        val powerManager = activity.getSystemService(android.os.PowerManager::class.java)
        if (powerManager?.isIgnoringBatteryOptimizations(activity.packageName) == true) {
            Toast.makeText(activity, R.string.perm_granted, Toast.LENGTH_SHORT).show()
            renderPermissions()
            return
        }
        val direct = android.content.Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            android.net.Uri.parse("package:${activity.packageName}")
        )
        runCatching { activity.startActivity(direct) }.onFailure {
            runCatching {
                activity.startActivity(android.content.Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }.onFailure {
                Toast.makeText(activity, R.string.perm_denied, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderShizuku() {
        val state = app.shizuku.state.value
        binding.shizukuStatusText.text = buildString {
            append(state.message)
            if (state.version > 0) append("\n版本：${state.version}")
            if (state.uid >= 0) append(" · uid ${state.uid}")
        }
        binding.shizukuCapsText.text = if (state.capabilities.isEmpty()) {
            activity.getString(R.string.settings_shizuku_caps) + "：暂无"
        } else {
            activity.getString(R.string.settings_shizuku_caps) + "：\n" +
                state.capabilities.joinToString("\n") { "· $it" }
        }
        binding.shizukuRequestButton.isEnabled = state.running && !state.permission
        binding.shizukuRequestButton.text = if (state.permission) {
            activity.getString(R.string.perm_granted)
        } else activity.getString(R.string.settings_shizuku_request)
    }

    private fun toggleOverlay() {
        if (!Settings.canDrawOverlays(activity)) {
            activity.requestOverlayPermission()
            return
        }
        OverlayService.start(activity)
        Toast.makeText(activity, R.string.settings_overlay, Toast.LENGTH_SHORT).show()
    }

    private fun showShizukuGuide() {
        val installed = app.shizuku.state.value.installed
        val message = buildString {
            appendLine("1. 安装 Shizuku（或 Sui/Magisk）。")
            appendLine("2. 通过无线调试或电脑 adb 启动 Shizuku 服务。")
            appendLine("3. 回到灵触，点击「请求授权」并在 Shizuku 弹窗中允许。")
            appendLine("4. 授权后灵触会以 shell 身份运行白名单系统命令。")
            if (!installed) appendLine("\n当前未检测到 Shizuku 应用。")
        }
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.settings_shizuku_guide)
            .setMessage(message)
            .setNegativeButton(R.string.action_close, null)
        if (installed) {
            builder.setPositiveButton(R.string.action_open) { _, _ ->
                activity.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    ?.let { runCatching { activity.startActivity(it) } }
            }
        }
        builder.show()
    }

    private fun cleanCache() {
        activity.lifecycleScope.launch {
            val freed = withContext(Dispatchers.IO) { app.temp.cleanupAll() }
            Toast.makeText(
                activity,
                activity.getString(R.string.settings_cleaned, Format.bytes(freed)),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun confirmClearTasks() {
        MaterialAlertDialogBuilder(activity)
            .setMessage(R.string.settings_clear_tasks_confirm)
            .setPositiveButton(R.string.action_clear) { _, _ ->
                activity.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { app.db.deleteAllTasks() }
                    Toast.makeText(activity, R.string.settings_saved, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmClearMemories() {
        MaterialAlertDialogBuilder(activity)
            .setMessage(R.string.settings_clear_memories_confirm)
            .setPositiveButton(R.string.action_clear) { _, _ ->
                activity.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { app.memory.clearAll() }
                    Toast.makeText(activity, R.string.settings_saved, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
