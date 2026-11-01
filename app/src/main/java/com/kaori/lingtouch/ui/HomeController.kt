package com.kaori.lingtouch.ui

import android.provider.Settings
import android.view.View
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kaori.lingtouch.LingTouchApp
import com.kaori.lingtouch.R
import com.kaori.lingtouch.accessibility.AccessibilityBridge
import com.kaori.lingtouch.agent.AgentStatus
import com.kaori.lingtouch.agent.AgentUiState
import com.kaori.lingtouch.databinding.ItemTaskBinding
import com.kaori.lingtouch.databinding.ViewHomeBinding
import com.kaori.lingtouch.memory.TaskEntity
import com.kaori.lingtouch.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeController(
    private val activity: MainActivity,
    private val binding: ViewHomeBinding
) {
    private val app get() = LingTouchApp.get(activity)

    init {
        binding.runButton.setOnClickListener { startTask() }
        binding.stopButton.setOnClickListener { app.agent.stop() }
        binding.detailsButton.setOnClickListener {
            val id = app.agent.state.value.taskId
            if (id > 0) activity.openTask(id)
        }
        binding.accessibilityStatus.setOnClickListener { activity.openPermissionGuide() }
        binding.overlayStatus.setOnClickListener { activity.openPermissionGuide() }
        binding.shizukuStatus.setOnClickListener { activity.openPermissionGuide() }
        binding.apiStatus.setOnClickListener { activity.showTab(R.id.tab_settings) }

        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.agent.state.collect { renderAgent(it) } }
                launch { app.shizuku.state.collect { renderPermissions() } }
                launch { app.settings.state.collect { renderPermissions() } }
                launch { app.settings.hasApiKey.collect { renderPermissions() } }
                launch { AccessibilityBridge.connected.collect { renderPermissions() } }
            }
        }
    }

    fun onShown() {
        renderAgent(app.agent.state.value)
        renderPermissions()
        loadRecent()
    }

    fun refresh() = onShown()

    private fun startTask() {
        val goal = binding.goalInput.text?.toString()?.trim().orEmpty()
        if (goal.isEmpty()) {
            binding.goalLayout.error = activity.getString(R.string.home_input_hint)
            return
        }
        binding.goalLayout.error = null
        if (!app.agent.start(goal)) {
            val error = app.agent.state.value.error ?: activity.getString(R.string.task_failed)
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.home_status)
                .setMessage(error)
                .setPositiveButton(R.string.action_grant) { _, _ -> activity.openPermissionGuide() }
                .setNegativeButton(R.string.action_close, null)
                .show()
            return
        }
        binding.goalInput.setText("")
        activity.openTask(app.agent.state.value.taskId)
    }

    private fun renderAgent(state: AgentUiState) {
        binding.agentStateBadge.text = when (state.status) {
            AgentStatus.IDLE -> activity.getString(R.string.home_ready)
            AgentStatus.RUNNING -> activity.getString(R.string.home_running)
            AgentStatus.PAUSED -> activity.getString(R.string.home_paused)
            AgentStatus.WAITING_CONFIRMATION -> activity.getString(R.string.task_confirm_title)
            AgentStatus.COMPLETED -> activity.getString(R.string.task_done)
            AgentStatus.FAILED -> activity.getString(R.string.task_failed)
            AgentStatus.CANCELLED -> activity.getString(R.string.task_cancelled)
        }
        val status = buildString {
            if (state.status == AgentStatus.IDLE) {
                append(activity.getString(R.string.home_ready))
            } else {
                if (state.goal.isNotBlank()) appendLine(state.goal.take(80))
                append(state.message)
                if (state.steps.isNotEmpty()) append(" · ${state.steps.size} 步")
            }
        }
        binding.statusText.text = status
        binding.agentProgress.visibility = if (state.isActive) View.VISIBLE else View.GONE
        binding.runButton.isEnabled = !state.isActive
        binding.stopButton.isEnabled = state.isActive
        binding.detailsButton.visibility = if (state.taskId > 0) View.VISIBLE else View.GONE
        binding.agentStateBadge.backgroundTintList = ContextCompat.getColorStateList(
            activity,
            when (state.status) {
                AgentStatus.RUNNING -> R.color.lt_success
                AgentStatus.FAILED -> R.color.lt_error
                else -> R.color.lt_primary_container
            }
        )
    }

    private fun renderPermissions() {
        val shizuku = app.shizuku.state.value
        val a11y = AccessibilityBridge.isConnected()
        val a11yEnabled = AccessibilityBridge.isEnabledInSettings(activity)
        binding.accessibilityStatus.text = activity.getString(R.string.settings_accessibility) + "：" +
            when {
                a11y -> activity.getString(R.string.perm_granted)
                a11yEnabled -> "已开启，正在连接"
                else -> activity.getString(R.string.perm_denied)
            }
        val overlay = Settings.canDrawOverlays(activity)
        binding.overlayStatus.text = activity.getString(R.string.settings_overlay) + "：" +
            if (overlay) activity.getString(R.string.perm_granted) else activity.getString(R.string.perm_denied)
        binding.shizukuStatus.text = "Shizuku：" + when {
            !shizuku.installed -> activity.getString(R.string.perm_not_installed)
            !shizuku.running -> activity.getString(R.string.perm_stopped)
            !shizuku.permission -> activity.getString(R.string.perm_denied)
            else -> activity.getString(R.string.perm_granted)
        }
        val configured = app.settings.hasApiKey.value
        val model = app.settings.state.value.model
        binding.apiStatus.text = activity.getString(R.string.home_api_status) + "：" +
            if (configured) "${activity.getString(R.string.home_api_configured)} · $model"
            else activity.getString(R.string.home_api_unconfigured)
    }

    private fun loadRecent() {
        activity.lifecycleScope.launch {
            val tasks = withContext(Dispatchers.IO) { app.db.recentTasks(6) }
            binding.recentContainer.removeAllViews()
            binding.recentEmpty.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
            tasks.forEach { task -> binding.recentContainer.addView(buildRow(task)) }
        }
    }

    private fun buildRow(task: TaskEntity): View {
        val row = ItemTaskBinding.inflate(activity.layoutInflater, binding.recentContainer, false)
        row.taskRowGoal.text = task.goal
        row.taskRowMeta.text = "${Format.time(task.startedAt)} · ${task.stepCount} 步"
        row.taskRowStatus.text = when (task.status) {
            "SUCCESS" -> activity.getString(R.string.status_success)
            "FAILED" -> activity.getString(R.string.status_failed)
            "CANCELLED" -> activity.getString(R.string.status_cancelled)
            "RUNNING" -> activity.getString(R.string.status_running)
            else -> task.status
        }
        row.taskRowStatus.setTextColor(
            ContextCompat.getColor(
                activity,
                when (task.status) {
                    "SUCCESS" -> R.color.lt_success
                    "FAILED" -> R.color.lt_error
                    "RUNNING" -> R.color.lt_primary
                    else -> R.color.lt_text_secondary
                }
            )
        )
        row.root.setOnClickListener { activity.openTask(task.id) }
        return row.root
    }
}
