package com.kaori.lingtouch.ui

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.kaori.lingtouch.LingTouchApp
import com.kaori.lingtouch.R
import com.kaori.lingtouch.agent.AgentStatus
import com.kaori.lingtouch.agent.AgentStepUi
import com.kaori.lingtouch.agent.AgentUiState
import com.kaori.lingtouch.databinding.ActivityTaskBinding
import com.kaori.lingtouch.memory.TaskEntity
import com.kaori.lingtouch.memory.TaskStepEntity
import com.kaori.lingtouch.ui.adapter.StepAdapter
import com.kaori.lingtouch.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TaskActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TASK_ID = "task_id"
    }

    private lateinit var binding: ActivityTaskBinding
    private val adapter = StepAdapter()
    private val app get() = LingTouchApp.get(this)
    private var taskId: Long = -1L
    private var historyLoaded = false
    private var loadedTask: TaskEntity? = null
    private var loadedSteps: List<TaskStepEntity> = emptyList()
    private var lastState: AgentUiState? = null
    private var lastReasoning = ""
    private var reasoningExpanded = false
    private var pendingExport = ""

    private val createDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri -> if (uri != null) writeExport(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTaskBinding.inflate(layoutInflater)
        setContentView(binding.root)
        taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)

        binding.taskToolbar.setNavigationOnClickListener { finish() }
        binding.stepsList.layoutManager = LinearLayoutManager(this)
        binding.stepsList.adapter = adapter

        binding.stopTaskButton.setOnClickListener { app.agent.stop() }
        binding.pauseTaskButton.setOnClickListener {
            if (app.agent.state.value.status == AgentStatus.PAUSED) app.agent.resume() else app.agent.pause()
        }
        binding.taskReasoningToggle.setOnClickListener {
            reasoningExpanded = !reasoningExpanded
            renderReasoning()
        }
        binding.exportTaskButton.setOnClickListener { exportTask() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.agent.state.collect { render(it) } }
            }
        }
    }

    private fun render(state: AgentUiState) {
        if (state.taskId == taskId && state.status != AgentStatus.IDLE) {
            renderLive(state)
        } else if (!historyLoaded) {
            historyLoaded = true
            loadHistory()
        }
    }

    private fun renderLive(state: AgentUiState) {
        lastState = state
        binding.taskGoal.text = state.goal
        binding.taskStatus.text = statusLabel(state.status)
        binding.taskStatus.setTextColor(
            ContextCompat.getColor(
                this,
                when (state.status) {
                    AgentStatus.SUCCEEDED -> R.color.lt_success
                    AgentStatus.FAILED -> R.color.lt_error
                    AgentStatus.RUNNING, AgentStatus.PLANNING -> R.color.lt_primary
                    AgentStatus.RECOVERING, AgentStatus.WAITING_USER -> R.color.lt_warning
                    else -> R.color.lt_text_primary
                }
            )
        )
        binding.taskMessage.text = state.message
        binding.taskPlan.text = planText(state.plan, state.subGoals.map { it.line() })
        binding.taskBudget.text = state.budgetText
        binding.taskProgress.visibility = if (state.isActive) View.VISIBLE else View.GONE
        binding.stopTaskButton.isEnabled = state.isActive
        binding.pauseTaskButton.isEnabled = state.isActive
        binding.pauseTaskButton.text = if (state.status == AgentStatus.PAUSED) {
            getString(R.string.overlay_resume)
        } else {
            getString(R.string.overlay_pause)
        }
        lastReasoning = state.reasoning
        renderReasoning()
        binding.taskEmpty.visibility = if (state.steps.isEmpty()) View.VISIBLE else View.GONE
        binding.stepsList.visibility = if (state.steps.isEmpty()) View.GONE else View.VISIBLE
        adapter.submitList(state.steps)
        if (state.steps.isNotEmpty()) {
            binding.stepsList.scrollToPosition(maxOf(0, state.steps.size - 1))
        }
    }

    private fun renderReasoning() {
        val hasReasoning = lastReasoning.isNotBlank() || lastState?.status?.let { it == AgentStatus.PLANNING } == true
        binding.taskReasoningToggle.visibility = if (hasReasoning) View.VISIBLE else View.GONE
        binding.taskReasoning.visibility = if (hasReasoning && reasoningExpanded) View.VISIBLE else View.GONE
        binding.taskReasoningToggle.text = if (reasoningExpanded) {
            getString(R.string.task_thinking_hide)
        } else {
            getString(R.string.task_thinking)
        }
        binding.taskReasoning.text = lastReasoning.ifBlank { getString(R.string.task_thinking_waiting) }
    }

    private fun loadHistory() {
        if (taskId <= 0) {
            binding.taskStatus.text = getString(R.string.home_no_tasks)
            return
        }
        lifecycleScope.launch {
            val pair = withContext(Dispatchers.IO) {
                val task = app.db.task(taskId)
                val steps = app.db.steps(taskId)
                task to steps
            }
            val task = pair.first ?: run {
                binding.taskStatus.text = getString(R.string.home_no_tasks)
                return@launch
            }
            val steps = pair.second
            loadedTask = task
            loadedSteps = steps
            binding.taskGoal.text = task.goal
            binding.taskStatus.text = statusLabel(task.status)
            binding.taskMessage.text = task.summary ?: ""
            binding.taskPlan.text = planTextFromJson(task.plan)
            binding.taskBudget.text = ""
            binding.taskProgress.visibility = View.GONE
            binding.stopTaskButton.isEnabled = false
            binding.pauseTaskButton.isEnabled = false
            binding.taskEmpty.visibility = if (steps.isEmpty()) View.VISIBLE else View.GONE
            binding.stepsList.visibility = if (steps.isEmpty()) View.GONE else View.VISIBLE
            adapter.submitList(
                steps.map {
                    AgentStepUi(it.stepIndex + 1, it.action, "", it.ok, resultMessage(it.resultJson), at = it.createdAt)
                }
            )
            renderReasoning()
        }
    }

    private fun exportTask() {
        pendingExport = buildExport()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        createDocument.launch("lingtouch-task-$taskId-$stamp.md")
    }

    private fun buildExport(): String = buildString {
        val state = lastState
        val task = loadedTask
        append("# 灵触任务记录\n\n")
        append("- 任务 ID：").append(taskId).append('\n')
        append("- 目标：").append(state?.goal ?: task?.goal.orEmpty()).append('\n')
        append("- 状态：").append(state?.status?.name ?: task?.status.orEmpty()).append('\n')
        append("- 导出时间：").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
        if (state != null && state.startedAt > 0) {
            append("- 开始时间：").append(Format.time(state.startedAt)).append('\n')
        }
        append("\n## 最终结果\n\n")
        append(state?.result?.ifBlank { state.message } ?: task?.summary.orEmpty()).append('\n')
        val plan = state?.plan?.ifBlank { null } ?: planTextFromJson(task?.plan)
        if (plan.isNotBlank()) {
            append("\n## 执行计划\n\n").append(plan.trim()).append('\n')
        }
        if (lastReasoning.isNotBlank()) {
            append("\n## 模型思考过程\n\n").append(lastReasoning.trim()).append('\n')
        }
        append("\n## 执行步骤\n\n")
        val steps = state?.steps ?: loadedSteps.map {
            AgentStepUi(it.stepIndex + 1, it.action, "", it.ok, resultMessage(it.resultJson), at = it.createdAt)
        }
        if (steps.isEmpty()) {
            append("（无步骤记录）\n")
        } else {
            steps.forEach { step ->
                append("- ").append(if (step.ok) "✓" else "✗").append(' ')
                append(step.action)
                if (step.detail.isNotBlank()) append(" · ").append(step.detail)
                if (step.message.isNotBlank()) append(" — ").append(step.message)
                append('\n')
            }
        }
    }

    private fun writeExport(uri: Uri) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = contentResolver.openOutputStream(uri, "wt")
                        ?: contentResolver.openOutputStream(uri, "w")
                    stream?.use { it.write(pendingExport.toByteArray(Charsets.UTF_8)) }
                    stream != null
                }.getOrDefault(false)
            }
            Toast.makeText(
                this@TaskActivity,
                if (ok) getString(R.string.export_done) else getString(R.string.export_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun planText(progress: String, lines: List<String>): String = buildString {
        if (progress.isNotBlank()) appendLine(progress)
        lines.forEach { appendLine(it) }
    }.trim()

    private fun planTextFromJson(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return runCatching {
            val root = JSONObject(raw)
            val array = root.optJSONArray("subgoals") ?: return@runCatching ""
            buildString {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    appendLine("${statusMarker(item.optString("status"))} ${item.optString("title")}")
                }
            }.trim()
        }.getOrDefault("")
    }

    private fun statusMarker(status: String): String = when (status) {
        "SUCCEEDED" -> "✔"
        "FAILED" -> "✘"
        "BLOCKED" -> "!"
        "SKIPPED" -> "–"
        "RUNNING" -> "▶"
        else -> "○"
    }

    private fun resultMessage(json: String?): String {
        if (json.isNullOrBlank()) return ""
        return runCatching {
            val root = JSONObject(json)
            val commit = root.optString("commit")
            val message = root.optString("message")
            if (commit.isNotBlank()) "[$commit] $message" else message
        }.getOrDefault("")
    }

    private fun statusLabel(status: AgentStatus): String = when (status) {
        AgentStatus.IDLE -> getString(R.string.home_ready)
        AgentStatus.PLANNING -> getString(R.string.home_running)
        AgentStatus.RUNNING -> getString(R.string.home_running)
        AgentStatus.PAUSED -> getString(R.string.home_paused)
        AgentStatus.WAITING_USER -> getString(R.string.task_confirm_title)
        AgentStatus.RECOVERING -> getString(R.string.status_running)
        AgentStatus.SUCCEEDED -> getString(R.string.task_done)
        AgentStatus.FAILED -> getString(R.string.task_failed)
        AgentStatus.CANCELLED -> getString(R.string.task_cancelled)
    }

    private fun statusLabel(raw: String): String = when (raw) {
        "SUCCESS" -> getString(R.string.task_done)
        "FAILED" -> getString(R.string.task_failed)
        "CANCELLED" -> getString(R.string.task_cancelled)
        "RUNNING" -> getString(R.string.home_running)
        else -> raw
    }
}
