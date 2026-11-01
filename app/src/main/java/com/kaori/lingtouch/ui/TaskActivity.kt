package com.kaori.lingtouch.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
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
import com.kaori.lingtouch.ui.adapter.StepAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TaskActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TASK_ID = "task_id"
    }

    private lateinit var binding: ActivityTaskBinding
    private val adapter = StepAdapter()
    private val app get() = LingTouchApp.get(this)
    private var taskId: Long = -1L
    private var historyLoaded = false

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
        binding.taskGoal.text = state.goal
        binding.taskStatus.text = statusLabel(state.status)
        binding.taskStatus.setTextColor(
            ContextCompat.getColor(
                this,
                when (state.status) {
                    AgentStatus.COMPLETED -> R.color.lt_success
                    AgentStatus.FAILED -> R.color.lt_error
                    AgentStatus.RUNNING -> R.color.lt_primary
                    else -> R.color.lt_text_primary
                }
            )
        )
        binding.taskMessage.text = state.message
        binding.taskProgress.visibility = if (state.isActive) View.VISIBLE else View.GONE
        binding.stopTaskButton.isEnabled = state.isActive
        binding.pauseTaskButton.isEnabled = state.isActive
        binding.pauseTaskButton.text = if (state.status == AgentStatus.PAUSED) {
            getString(R.string.overlay_resume)
        } else {
            getString(R.string.overlay_pause)
        }
        binding.taskEmpty.visibility = if (state.steps.isEmpty()) View.VISIBLE else View.GONE
        binding.stepsList.visibility = if (state.steps.isEmpty()) View.GONE else View.VISIBLE
        if (adapter.itemCount != state.steps.size) {
            adapter.submitList(state.steps)
            binding.stepsList.scrollToPosition(maxOf(0, state.steps.size - 1))
        } else {
            adapter.submitList(state.steps)
        }
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
            binding.taskGoal.text = task.goal
            binding.taskStatus.text = statusLabel(task.status)
            binding.taskMessage.text = task.summary ?: ""
            binding.taskProgress.visibility = View.GONE
            binding.stopTaskButton.isEnabled = false
            binding.pauseTaskButton.isEnabled = false
            binding.taskEmpty.visibility = if (steps.isEmpty()) View.VISIBLE else View.GONE
            binding.stepsList.visibility = if (steps.isEmpty()) View.GONE else View.VISIBLE
            adapter.submitList(
                steps.map {
                    AgentStepUi(it.stepIndex + 1, it.action, "", it.ok, resultMessage(it.resultJson), it.createdAt)
                }
            )
        }
    }

    private fun resultMessage(json: String?): String {
        if (json.isNullOrBlank()) return ""
        return runCatching {
            org.json.JSONObject(json).optString("message")
        }.getOrDefault("")
    }

    private fun statusLabel(status: AgentStatus): String = when (status) {
        AgentStatus.IDLE -> getString(R.string.home_ready)
        AgentStatus.RUNNING -> getString(R.string.home_running)
        AgentStatus.PAUSED -> getString(R.string.home_paused)
        AgentStatus.WAITING_CONFIRMATION -> getString(R.string.task_confirm_title)
        AgentStatus.COMPLETED -> getString(R.string.task_done)
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
