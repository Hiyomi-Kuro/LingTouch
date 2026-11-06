package com.kaori.lingtouch.agent

import android.content.Context
import android.content.Intent
import com.kaori.lingtouch.R
import com.kaori.lingtouch.accessibility.AccessibilityBridge
import com.kaori.lingtouch.api.ModelCatalog
import com.kaori.lingtouch.api.OpenCodeClient
import com.kaori.lingtouch.apps.AppRepository
import com.kaori.lingtouch.memory.LingTouchDb
import com.kaori.lingtouch.memory.MemoryRepository
import com.kaori.lingtouch.ocr.OcrEngine
import com.kaori.lingtouch.settings.SettingsRepository
import com.kaori.lingtouch.shizuku.ShizukuManager
import com.kaori.lingtouch.chat.ChatMode
import com.kaori.lingtouch.chat.ChatStore
import com.kaori.lingtouch.storage.TempResourceManager
import com.kaori.lingtouch.tools.ToolExecutor
import com.kaori.lingtouch.ui.MainActivity
import com.kaori.lingtouch.util.Logx
import com.kaori.lingtouch.vision.ScreenCaptureManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Orchestrates one task at a time: validates preconditions, publishes the state machine,
 * delegates execution to [TaskRunner], and exposes pause/resume/stop/confirm to every UI.
 */
class AgentEngine(
    private val context: Context,
    private val settings: SettingsRepository,
    private val db: LingTouchDb,
    private val memory: MemoryRepository,
    private val apps: AppRepository,
    private val shizuku: ShizukuManager,
    private val api: OpenCodeClient,
    private val catalog: ModelCatalog,
    private val capture: ScreenCaptureManager,
    private val ocr: OcrEngine,
    private val temp: TempResourceManager,
    private val tools: ToolExecutor,
    private val observations: ObservationBuilder,
    val confirmation: ConfirmationBroker
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(AgentUiState())
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    private var job: Job? = null
    @Volatile private var paused = false
    @Volatile private var cancelled = false
    private val startLock = Any()
    private val chatStore = ChatStore(db)
    @Volatile private var activeModel = ""
    private val runner = TaskRunner(
        context, settings, db, memory, apps, api, tools, observations, temp, confirmation,
        _state, { cancelled }, { paused },
        onFinished = { handleFinished(it) },
        modeMemory = { chatStore.modeMemory(ChatMode.CONTROL) },
        modelOverride = { activeModel }
    )

    init {
        scope.launch {
            confirmation.pending.collect { request ->
                _state.update { it.copy(pendingRequest = request) }
                if (request != null) openMainForConfirmation()
            }
        }
    }

    fun start(goal: String, model: String = ""): Boolean = synchronized(startLock) {
        if (job?.isActive == true || _state.value.isActive) return@synchronized false
        val trimmed = goal.trim()
        if (trimmed.isEmpty()) return@synchronized false
        val config = settings.state.value
        val selectedModel = model.trim().ifBlank { config.model }
        val problem = when {
            settings.apiKey().isNullOrBlank() -> context.getString(R.string.error_api_key_missing)
            selectedModel.isBlank() -> context.getString(R.string.error_no_model)
            !AccessibilityBridge.isConnected() -> context.getString(R.string.error_no_accessibility)
            else -> null
        }
        if (problem != null) {
            Logx.w("Agent", "任务未启动：$problem")
            _state.value = AgentUiState(
                status = AgentStatus.FAILED,
                goal = trimmed,
                error = problem,
                message = problem,
                finishedAt = System.currentTimeMillis()
            )
            return@synchronized false
        }
        val taskId = db.createTask(trimmed)
        settings.setLastTaskId(taskId)
        activeModel = selectedModel
        cancelled = false
        paused = false
        _state.value = AgentUiState(
            status = AgentStatus.PLANNING,
            taskId = taskId,
            goal = trimmed,
            plan = context.getString(R.string.task_plan_empty),
            message = "准备执行…",
            startedAt = System.currentTimeMillis()
        )
        AgentService.start(context)
        val newJob = scope.launch {
            try {
                runner.run(taskId, trimmed)
            } catch (t: Throwable) {
                Logx.e("Agent", "任务协程异常", t)
                _state.update {
                    it.copy(
                        status = AgentStatus.FAILED,
                        error = t.message ?: t.javaClass.simpleName,
                        message = t.message ?: context.getString(R.string.task_failed),
                        finishedAt = System.currentTimeMillis(),
                        pendingRequest = null
                    )
                }
                runCatching { AgentService.stop(context) }
                handleFinished(taskId)
            }
        }
        job = newJob
        true
    }

    fun stop() {
        if (!_state.value.isActive) return
        cancelled = true
        confirmation.cancelPending()
        _state.update {
            it.copy(status = AgentStatus.CANCELLED, message = context.getString(R.string.task_stopping))
        }
        job?.cancel()
    }

    fun pause() {
        val status = _state.value.status
        if (status != AgentStatus.RUNNING && status != AgentStatus.PLANNING && status != AgentStatus.RECOVERING) return
        paused = true
        _state.update {
            it.copy(status = AgentStatus.PAUSED, message = context.getString(R.string.status_paused))
        }
    }

    fun resume() {
        if (_state.value.status != AgentStatus.PAUSED) return
        paused = false
        _state.update { it.copy(status = AgentStatus.RUNNING, message = "继续执行…") }
    }

    fun confirm(id: Long, allowed: Boolean): Boolean = confirmation.resolve(id, allowed)

    fun isBusy(): Boolean = job?.isActive == true

    private fun handleFinished(taskId: Long) {
        job = null
        scope.launch { runCatching { AgentService.stop(context) } }
        if (!cancelled) openMainForResult(taskId)
    }

    private fun openMainForConfirmation() {
        openMain(Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SHOW_CONFIRMATION, true))
    }

    private fun openMainForResult(taskId: Long) {
        openMain(Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_RESULT_TASK_ID, taskId))
    }

    private fun openMain(intent: Intent) {
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        runCatching { context.startActivity(intent) }
    }
}
