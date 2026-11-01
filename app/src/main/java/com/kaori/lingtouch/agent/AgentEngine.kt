package com.kaori.lingtouch.agent

import android.content.Context
import android.content.Intent
import com.kaori.lingtouch.R
import com.kaori.lingtouch.accessibility.AccessibilityBridge
import com.kaori.lingtouch.api.ApiMessage
import com.kaori.lingtouch.api.ApiOutcome
import com.kaori.lingtouch.api.ModelCatalog
import com.kaori.lingtouch.api.OpenCodeClient
import com.kaori.lingtouch.apps.AppRepository
import com.kaori.lingtouch.memory.LingTouchDb
import com.kaori.lingtouch.memory.MemoryContext
import com.kaori.lingtouch.memory.MemoryRepository
import com.kaori.lingtouch.memory.TaskStepEntity
import com.kaori.lingtouch.ocr.OcrEngine
import com.kaori.lingtouch.settings.SettingsRepository
import com.kaori.lingtouch.shizuku.ShizukuManager
import com.kaori.lingtouch.storage.TempResourceManager
import com.kaori.lingtouch.tools.ActionParser
import com.kaori.lingtouch.tools.ActionValidationException
import com.kaori.lingtouch.tools.AgentAction
import com.kaori.lingtouch.tools.Risk
import com.kaori.lingtouch.tools.ToolExecutor
import com.kaori.lingtouch.tools.ToolRegistry
import com.kaori.lingtouch.ui.MainActivity
import com.kaori.lingtouch.util.Logx
import com.kaori.lingtouch.util.int
import com.kaori.lingtouch.util.str
import com.kaori.lingtouch.vision.ScreenCaptureManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Closed execution loop: Observe → Plan → Act → Verify → Reflect → Continue.
 * One action per model turn, every action schema-validated, every risky action confirmed,
 * with step/timeout/repeat guards and post-task cleanup + learning.
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

    init {
        scope.launch {
            confirmation.pending.collect { request ->
                _state.update { it.copy(pendingRequest = request) }
                if (request != null) openMainForConfirmation()
            }
        }
    }

    fun start(goal: String): Boolean {
        if (job?.isActive == true) return false
        val trimmed = goal.trim()
        if (trimmed.isEmpty()) return false
        val config = settings.state.value
        val problem = when {
            settings.apiKey().isNullOrBlank() -> context.getString(R.string.error_api_key_missing)
            config.model.isBlank() -> context.getString(R.string.error_no_model)
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
                maxSteps = config.maxSteps,
                finishedAt = System.currentTimeMillis()
            )
            return false
        }

        val taskId = db.createTask(trimmed)
        settings.setLastTaskId(taskId)
        cancelled = false
        paused = false
        _state.value = AgentUiState(
            status = AgentStatus.RUNNING,
            taskId = taskId,
            goal = trimmed,
            maxSteps = config.maxSteps,
            message = "准备执行…",
            startedAt = System.currentTimeMillis()
        )
        AgentService.start(context)
        job = scope.launch { runTask(taskId, trimmed) }
        return true
    }

    fun stop() {
        if (!_state.value.isActive) return
        cancelled = true
        confirmation.cancelPending()
        _state.update { it.copy(status = AgentStatus.CANCELLED, message = context.getString(R.string.task_stopping)) }
        job?.cancel()
    }

    fun pause() {
        if (_state.value.status != AgentStatus.RUNNING) return
        paused = true
        _state.update { it.copy(status = AgentStatus.PAUSED, message = context.getString(R.string.status_paused)) }
    }

    fun resume() {
        if (_state.value.status != AgentStatus.PAUSED) return
        paused = false
        _state.update { it.copy(status = AgentStatus.RUNNING, message = "继续执行…") }
    }

    fun confirm(id: Long, allowed: Boolean): Boolean = confirmation.resolve(id, allowed)

    fun isBusy(): Boolean = job?.isActive == true

    private suspend fun runTask(taskId: Long, goal: String) {
        val config = settings.state.value
        val history = ArrayList<String>()
        val signatures = HashMap<String, Int>()
        var stepIndex = 0
        var success = false
        var summary: String? = null
        var failure: String? = null
        var executedOk = 0
        var lastResult: JSONObject? = null
        var formatErrors = 0
        var memoryContext: MemoryContext = memory.contextFor(goal, AccessibilityBridge.foregroundPackage().takeIf { it.isNotBlank() })
        var memoryPackage: String = AccessibilityBridge.foregroundPackage()

        try {
            while (true) {
                if (cancelled) throw CancellationException("cancelled")
                awaitResume()
                coroutineContext.ensureActive()

                val tree = AccessibilityBridge.snapshot()
                if (tree == null && !AccessibilityBridge.isConnected()) {
                    failure = context.getString(R.string.error_no_accessibility)
                    break
                }
                val observedPackage = tree?.packageName.orEmpty()
                if (observedPackage.isNotBlank() && observedPackage != memoryPackage) {
                    memoryPackage = observedPackage
                    memoryContext = memory.contextFor(goal, observedPackage)
                }
                _state.update { it.copy(message = "观察界面…", currentStep = stepIndex) }
                val observation = observations.build(
                    taskId, goal, stepIndex + 1, 0, history,
                    memoryContext.toPromptText(), lastResult, tree
                )
                _state.update { it.copy(message = "思考中…") }

                val messages = listOf(
                    ApiMessage("system", PromptBuilder.system()),
                    ApiMessage("user", PromptBuilder.user(observation.json), observation.images)
                )
                val modelForStep = if (observation.images.isNotEmpty() && config.visionModel.isNotBlank()) {
                    config.visionModel
                } else {
                    config.model
                }
                var outcome = api.chat(modelForStep, messages, jsonMode = true, maxTokens = 1600)
                if (outcome is ApiOutcome.Err && outcome.error.kind == com.kaori.lingtouch.api.ApiErrorKind.BAD_REQUEST) {
                    // Some providers/models reject response_format; retry once with plain text JSON instructions.
                    outcome = api.chat(modelForStep, messages, jsonMode = false, maxTokens = 1600)
                }
                when (outcome) {
                    is ApiOutcome.Err -> {
                        failure = outcome.error.message
                        break
                    }
                    is ApiOutcome.Ok -> {
                        val action = try {
                            ActionParser.parse(outcome.value.text)
                        } catch (e: ActionValidationException) {
                            formatErrors++
                            history.add("第 ${stepIndex + 1} 步输出格式错误：${e.message}")
                            _state.update { it.copy(message = "输出格式错误，正在纠正…") }
                            if (formatErrors >= 2) {
                                failure = e.message
                                break
                            }
                            stepIndex++
                            continue
                        }
                        formatErrors = 0

                        if (action.name == "finish_task") {
                            val rejection = verifyFinish(action, tree, executedOk)
                            if (rejection != null) {
                                history.add("完成被拒绝：$rejection")
                                _state.update { it.copy(message = "完成验证未通过：$rejection") }
                                stepIndex++
                                continue
                            }
                            success = true
                            summary = action.args.str("summary").ifBlank { "任务已完成" }
                            break
                        }
                        if (action.name == "fail_task") {
                            failure = action.args.str("reason").ifBlank { "模型判断任务无法完成" }
                            break
                        }

                        val validated = try {
                            ToolRegistry.validate(action.name, action.args)
                        } catch (e: ActionValidationException) {
                            history.add("参数错误：${e.message}")
                            _state.update { it.copy(message = "参数错误，正在纠正…") }
                            stepIndex++
                            continue
                        }

                        var risk = action.declaredRisk
                        ToolRegistry.find(action.name)?.let { risk = RiskPolicy.max(risk, it.risk) }
                        risk = RiskPolicy.max(risk, RiskPolicy.assess(action.name, validated))
                        if (risk == Risk.HIGH) {
                            _state.update { it.copy(status = AgentStatus.WAITING_CONFIRMATION, message = "等待用户确认…") }
                            val allowed = confirmation.request(describeAction(action.name, validated), risk)
                            if (cancelled) throw CancellationException("cancelled")
                            _state.update {
                                it.copy(
                                    status = AgentStatus.RUNNING,
                                    message = if (allowed) "用户已允许高风险操作" else "用户已拒绝高风险操作"
                                )
                            }
                            if (!allowed) {
                                history.add("用户拒绝执行 ${action.name}；不得绕过该确认，请改变方案。")
                                stepIndex++
                                continue
                            }
                        }

                        val signature = "${action.name}:$validated"
                        var repeats = (signatures[signature] ?: 0) + 1
                        signatures[signature] = repeats
                        if (repeats >= 3) {
                            _state.update {
                                it.copy(status = AgentStatus.WAITING_CONFIRMATION, message = "检测到重复操作，等待你的选择…")
                            }
                            val repeatReason = context.getString(
                                R.string.repeat_detected,
                                repeatActionLabel(action.name, validated),
                                repeats
                            )
                            val keepGoing = confirmation.request(
                                repeatReason,
                                Risk.MEDIUM,
                                confirmLabel = context.getString(R.string.action_continue),
                                denyLabel = context.getString(R.string.action_stop)
                            )
                            if (cancelled) throw CancellationException("cancelled")
                            _state.update {
                                it.copy(
                                    status = AgentStatus.RUNNING,
                                    message = if (keepGoing) "已选择继续执行" else "已选择停止任务"
                                )
                            }
                            if (!keepGoing) {
                                failure = context.getString(R.string.repeat_stopped)
                                break
                            }
                            // 用户选择继续后清零计数，下次连续重复会再次询问。
                            signatures[signature] = 0
                            repeats = 0
                        }

                        _state.update { it.copy(status = AgentStatus.RUNNING, message = "执行 ${action.name}…") }
                        val started = System.currentTimeMillis()
                        var result = tools.execute(taskId, action.copy(args = validated))
                        if (!result.ok && result.retryable && config.maxRetries > 0) {
                            delay(500)
                            result = tools.execute(taskId, action.copy(args = validated))
                        }
                        val duration = System.currentTimeMillis() - started
                        val resultJson = JSONObject()
                            .put("ok", result.ok)
                            .put("message", result.message)
                            .put("data", result.data)
                        lastResult = resultJson
                        runCatching {
                            db.addStep(
                                TaskStepEntity(
                                    taskId = taskId,
                                    stepIndex = stepIndex,
                                    action = action.name,
                                    argsJson = validated.toString(),
                                    resultJson = resultJson.toString(),
                                    ok = result.ok,
                                    durationMs = duration
                                )
                            )
                        }
                        if (result.ok) executedOk++
                        _state.update {
                            it.copy(
                                steps = it.steps + AgentStepUi(
                                    index = stepIndex + 1,
                                    action = action.name,
                                    detail = summarizeArgs(validated),
                                    ok = result.ok,
                                    message = result.message
                                ),
                                currentStep = stepIndex + 1,
                                message = result.message
                            )
                        }
                        history.add(
                            "${stepIndex + 1}. ${action.name}(${summarizeArgs(validated)}) → " +
                                "${if (result.ok) "成功" else "失败"}：${result.message}"
                        )
                        stepIndex++
                    }
                }
            }
        } catch (e: CancellationException) {
            cancelled = true
        } catch (t: Throwable) {
            Logx.e("Agent", "任务执行异常", t)
            failure = t.message ?: t.javaClass.simpleName
        } finally {
            finalize(taskId, goal, success, summary, failure, memoryContext)
        }
    }

    private suspend fun awaitResume() {
        while (paused && !cancelled) delay(200)
    }

    private fun verifyFinish(action: AgentAction, snapshot: com.kaori.lingtouch.accessibility.UiTree?, executedOk: Int): String? {
        if (executedOk == 0) return "尚未成功执行任何设备操作，不能结束任务"
        val expectPackage = action.args.str("expect_package")
        val expectText = action.args.str("expect_text")
        val evidence = action.args.str("evidence")
        val current = AccessibilityBridge.snapshot() ?: snapshot
        val foreground = current?.packageName?.takeIf { it.isNotBlank() } ?: AccessibilityBridge.foregroundPackage()
        if (expectPackage.isNotBlank() && foreground != expectPackage) {
            return "当前前台是 $foreground，不是期望的 $expectPackage"
        }
        if (expectText.isNotBlank()) {
            val found = current?.nodes?.any { it.matchesText(expectText, true) } ?: false
            if (!found) return "界面中未找到「$expectText」"
        }
        if (evidence.isBlank() && expectText.isBlank() && expectPackage.isBlank()) {
            return "finish_task 需要 evidence，或 expect_text / expect_package 作为可验证证据"
        }
        return null
    }

    private suspend fun finalize(
        taskId: Long,
        goal: String,
        success: Boolean,
        summary: String?,
        failure: String?,
        memoryContext: MemoryContext
    ) = withContext(NonCancellable) {
        val status = when {
            cancelled -> "CANCELLED"
            success -> "SUCCESS"
            else -> "FAILED"
        }
        val finalMessage = when {
            cancelled -> context.getString(R.string.task_cancelled)
            success -> summary ?: context.getString(R.string.task_done)
            else -> failure ?: context.getString(R.string.task_failed)
        }
        runCatching { db.finishTask(taskId, status, finalMessage, success, _state.value.steps.size) }
        runCatching {
            val steps = db.steps(taskId)
            if (steps.isNotEmpty()) memory.learnFromTask(taskId, goal, steps, success, finalMessage)
            memory.recordUsage(memoryContext, success)
        }.onFailure { Logx.w("Agent", "记忆写入失败：${it.message}") }
        if (settings.state.value.autoCleanup) {
            runCatching { temp.cleanupTask(taskId) }
        }
        _state.update {
            it.copy(
                status = when {
                    cancelled -> AgentStatus.CANCELLED
                    success -> AgentStatus.COMPLETED
                    else -> AgentStatus.FAILED
                },
                result = finalMessage,
                error = if (!success && !cancelled) finalMessage else null,
                message = finalMessage,
                finishedAt = System.currentTimeMillis(),
                pendingRequest = null
            )
        }
        runCatching { AgentService.stop(context) }
        if (!cancelled) openMainForResult(taskId)
        job = null
    }

    private fun openMainForConfirmation() {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            .putExtra(MainActivity.EXTRA_SHOW_CONFIRMATION, true)
        runCatching { context.startActivity(intent) }
    }

    private fun openMainForResult(taskId: Long) {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            .putExtra(MainActivity.EXTRA_RESULT_TASK_ID, taskId)
        runCatching { context.startActivity(intent) }
    }

    private fun repeatActionLabel(name: String, args: JSONObject): String {
        val text = "$name ${args.toString()}"
        return if (text.length <= 120) text else text.take(120) + "…"
    }

    private fun describeAction(name: String, args: JSONObject): String {
        val text = args.toString()
        val detail = if (text.length <= 180) text else text.take(180) + "…"
        return "即将执行 $name $detail"
    }

    private fun summarizeArgs(args: JSONObject): String {
        val text = args.toString()
        return if (text.length <= 80) text else text.take(80) + "…"
    }
}
