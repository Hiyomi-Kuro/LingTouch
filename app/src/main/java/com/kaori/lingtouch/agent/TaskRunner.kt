package com.kaori.lingtouch.agent

import android.content.Context
import com.kaori.lingtouch.R
import com.kaori.lingtouch.accessibility.AccessibilityBridge
import com.kaori.lingtouch.accessibility.UiTree
import com.kaori.lingtouch.api.ApiError
import com.kaori.lingtouch.api.ApiErrorKind
import com.kaori.lingtouch.api.ApiMessage
import com.kaori.lingtouch.api.ApiOutcome
import com.kaori.lingtouch.api.OpenCodeClient
import com.kaori.lingtouch.apps.AppRepository
import com.kaori.lingtouch.memory.LingTouchDb
import com.kaori.lingtouch.memory.MemoryContext
import com.kaori.lingtouch.memory.MemoryRepository
import com.kaori.lingtouch.memory.TaskStepEntity
import com.kaori.lingtouch.security.Redactor
import com.kaori.lingtouch.settings.SettingsRepository
import com.kaori.lingtouch.storage.TempResourceManager
import com.kaori.lingtouch.tools.ActionParser
import com.kaori.lingtouch.tools.ActionValidationException
import com.kaori.lingtouch.tools.AgentAction
import com.kaori.lingtouch.tools.Risk
import com.kaori.lingtouch.tools.ToolExecutor
import com.kaori.lingtouch.tools.ToolRegistry
import com.kaori.lingtouch.util.Logx
import com.kaori.lingtouch.util.bool
import com.kaori.lingtouch.util.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Goal → Decompose → Observe → Select Strategy → Act → Verify → Recover/Reflect → Complete.
 *
 * The loop owns no UI: every state change is published to the shared [AgentUiState] flow.
 * Actions are recorded separately from their verification, and nothing is reported as done
 * unless the local verifier (or an explicit user confirmation) agrees.
 */
class TaskRunner(
    private val context: Context,
    private val settings: SettingsRepository,
    private val db: LingTouchDb,
    private val memory: MemoryRepository,
    private val apps: AppRepository,
    private val api: OpenCodeClient,
    private val tools: ToolExecutor,
    private val observations: ObservationBuilder,
    private val temp: TempResourceManager,
    private val confirmation: ConfirmationBroker,
    private val state: MutableStateFlow<AgentUiState>,
    private val cancelled: () -> Boolean,
    private val paused: () -> Boolean,
    private val onFinished: (Long) -> Unit,
    private val modeMemory: () -> String,
    private val modelOverride: () -> String = { "" },
) {
    private val compressor = ContextCompressor()
    private val loop = LoopDetector()

    suspend fun run(taskId: Long, goal: String) {
        val session = Session(taskId, goal)
        session.modeMemory = modeMemory()
        loop.reset()
        Logx.i("Agent", "任务 $taskId 开始：${goal.take(80)}")
        try {
            while (true) {
                if (cancelled()) throw CancellationException("cancelled")
                awaitResume()
                if (cycle(session)) break
            }
        } catch (e: CancellationException) {
            Logx.d("Agent", "任务 $taskId 被取消")
        } catch (t: Throwable) {
            Logx.e("Agent", "任务执行异常", t)
            session.failure = FailureInfo(
                FailureKind.UNKNOWN,
                t.message ?: t.javaClass.simpleName,
                session.stepIndex
            )
        } finally {
            finalize(session)
        }
    }

    // ------------------------------------------------------------------ loop

    private suspend fun cycle(s: Session): Boolean {
        val tree = AccessibilityBridge.snapshot()
        val connected = AccessibilityBridge.isConnected()
        if (tree == null && !connected) {
            s.failure = FailureInfo(
                FailureKind.PERMISSION_DENIED,
                context.getString(R.string.error_no_accessibility),
                s.stepIndex
            )
            return true
        }
        val screen = ScreenState.from(
            tree,
            accessibility = connected,
            ocr = settings.state.value.ocrEnabled
        )
        refreshMemory(s, screen.packageName)
        s.screen = screen
        publish(status = AgentStatus.RUNNING, message = "观察界面…", screen = screen.label)

        val memoryText = s.memoryContext.toPromptText()
        val contextSnapshot = compressor.build(
            goal = s.goal,
            constraints = s.plan.constraints,
            plan = s.plan,
            traces = s.traces,
            failures = s.failures,
            memoryText = memoryText,
            screen = screen,
            uiText = tree?.screenText().orEmpty()
        )
        publish(
            status = AgentStatus.PLANNING,
            message = "思考中…",
            plan = s.plan.progressText(),
            subGoals = s.plan.subGoals
        )
        val requestedModel = modelOverride().ifBlank { settings.state.value.model }
        val observation = observations.build(
            taskId = s.taskId,
            goal = s.goal,
            step = s.stepIndex + 1,
            plan = s.plan,
            contextSnapshot = contextSnapshot,
            screen = screen,
            lastResult = s.lastResult,
            tree = tree,
            selectedModel = requestedModel
        )
        s.budget = s.budget.estimate(observation.estimatedPromptTokens)
        publishBudget(s)

        val config = settings.state.value
        val model = observation.visionModel.ifBlank { requestedModel }
        val messages = listOf(
            ApiMessage("system", PromptBuilder.system(s.modeMemory)),
            ApiMessage("user", PromptBuilder.user(observation.json), observation.images)
        )
        val requestTimeout = config.timeoutSeconds.coerceIn(15, 600) * 1000L
        s.budget = s.budget.request()
        var outcome = requestJson(model, messages, jsonMode = true, requestTimeout)
        if (outcome is ApiOutcome.Err && outcome.error.kind == ApiErrorKind.BAD_REQUEST) {
            // Some providers reject response_format; retry once with plain JSON instructions.
            s.budget = s.budget.request()
            outcome = requestJson(model, messages, jsonMode = false, requestTimeout)
        }
        when (outcome) {
            is ApiOutcome.Err -> return handleApiError(s, outcome.error)
            is ApiOutcome.Ok -> {
                s.networkRetries = 0
                if (outcome.value.reasoning.isNotBlank()) {
                    s.reasoningLog.append(outcome.value.reasoning).append("\n\n")
                }
                s.budget = s.budget.usage(outcome.value.promptTokens, outcome.value.completionTokens)
                publishBudget(s)
                val action = try {
                    ActionParser.parse(outcome.value.text)
                } catch (e: ActionValidationException) {
                    return handleFormatError(s, e.message ?: "输出格式错误")
                }
                s.formatErrors = 0
                if (action.thought.isNotBlank()) {
                    s.reasoningLog.append("思考：").append(action.thought).append('\n')
                }
                publish(reasoning = s.reasoningLog.toString().takeLast(80_000))
                awaitResume()
                action.plan?.let { applyPlan(s, it) }
                return when (action.name) {
                    "finish_task" -> finishTask(s, action)
                    "fail_task" -> {
                        s.failure = FailureInfo(
                            FailureKind.UNKNOWN,
                            action.args.str("reason").ifBlank { "模型判断任务无法完成" },
                            s.stepIndex
                        )
                        true
                    }
                    else -> act(s, action)
                }
            }
        }
    }

    private suspend fun requestJson(
        model: String,
        messages: List<ApiMessage>,
        jsonMode: Boolean,
        timeoutMs: Long
    ): ApiOutcome<com.kaori.lingtouch.api.ChatOutput> {
        val outcome = withTimeoutOrNull(timeoutMs) {
            api.chat(model, messages, jsonMode = jsonMode, maxTokens = 1600)
        } ?: ApiOutcome.Err(
            ApiError(ApiErrorKind.TIMEOUT, context.getString(R.string.error_timeout), 0, true)
        )
        return outcome
    }

    private suspend fun handleApiError(s: Session, error: ApiError): Boolean {
        val kind = when (error.kind) {
            ApiErrorKind.RATE_LIMIT -> FailureKind.RATE_LIMIT
            ApiErrorKind.NETWORK, ApiErrorKind.TIMEOUT, ApiErrorKind.SERVER -> FailureKind.NETWORK_ERROR
            ApiErrorKind.UNAUTHORIZED, ApiErrorKind.FORBIDDEN -> FailureKind.PERMISSION_DENIED
            else -> FailureKind.UNKNOWN
        }
        val info = FailureInfo(kind, error.message, s.stepIndex)
        s.failures.add(info)
        if (kind != FailureKind.NETWORK_ERROR && kind != FailureKind.RATE_LIMIT) {
            s.failure = info
            return true
        }
        s.networkRetries++
        val recovery = RecoveryPolicy.planFor(kind, s.networkRetries)
        publish(
            status = AgentStatus.RECOVERING,
            message = "网络/限流恢复：${recovery.hint}",
            recovery = kind.label
        )
        delay(recovery.waitMs.coerceAtLeast(800))
        if (s.networkRetries >= 3) {
            publish(status = AgentStatus.WAITING_USER, message = "网络持续失败，等待你的决定")
            val retry = confirmation.request(
                "网络请求连续失败：${error.message.take(160)}\n\n是否继续等待并重试？",
                Risk.MEDIUM,
                confirmLabel = context.getString(R.string.action_continue),
                denyLabel = context.getString(R.string.action_stop)
            )
            if (cancelled()) throw CancellationException("cancelled")
            publish(status = AgentStatus.RUNNING)
            if (!retry) {
                s.failure = info
                return true
            }
            s.networkRetries = 0
        }
        return false
    }

    private fun handleFormatError(s: Session, message: String): Boolean {
        s.formatErrors++
        val info = FailureInfo(FailureKind.MODEL_FORMAT_ERROR, message, s.stepIndex)
        s.failures.add(info)
        record(
            s,
            "model_output",
            JSONObject(),
            com.kaori.lingtouch.tools.ActionResult(false, message, failure = FailureKind.MODEL_FORMAT_ERROR),
            ActionCommit.FAILED,
            message
        )
        publish(status = AgentStatus.RECOVERING, message = "输出格式错误，正在纠正…", recovery = info.kind.label)
        if (s.formatErrors >= 2) {
            s.failure = info
            return true
        }
        s.stepIndex++
        return false
    }

    // ------------------------------------------------------------------- act

    private suspend fun act(s: Session, action: AgentAction): Boolean {
        val validated = try {
            ToolRegistry.validate(action.name, action.args)
        } catch (e: ActionValidationException) {
            val message = e.message ?: "参数错误"
            val info = FailureInfo(FailureKind.INVALID_ARGS, message, s.stepIndex)
            s.failures.add(info)
            record(
                s,
                action.name,
                action.args,
                com.kaori.lingtouch.tools.ActionResult(false, message, failure = FailureKind.INVALID_ARGS),
                ActionCommit.FAILED,
                message
            )
            markCurrent(s, info)
            return recover(s, info)
        }

        var risk = action.declaredRisk
        ToolRegistry.find(action.name)?.let { risk = RiskPolicy.max(risk, it.risk) }
        risk = RiskPolicy.max(risk, RiskPolicy.assess(action.name, validated))
        if (risk == Risk.HIGH) {
            publish(status = AgentStatus.WAITING_USER, message = "等待用户确认高风险操作…")
            val allowed = confirmation.request(describeAction(action.name, validated), risk)
            if (cancelled()) throw CancellationException("cancelled")
            publish(status = AgentStatus.RUNNING)
            if (!allowed) {
                val denied = FailureInfo(
                    FailureKind.HIGH_RISK_DENIED,
                    "用户拒绝执行 ${action.name}",
                    s.stepIndex
                )
                s.failures.add(denied)
                record(
                    s,
                    action.name,
                    validated,
                    com.kaori.lingtouch.tools.ActionResult(
                        false,
                        "用户拒绝高风险操作",
                        failure = FailureKind.HIGH_RISK_DENIED
                    ),
                    ActionCommit.FAILED,
                    denied.message
                )
                s.failure = denied
                return true
            }
        }

        val signature = "${action.name}:${ToolExecutor.shortArgs(validated)}"
        loop.onAction(signature)?.let { signal -> if (handleLoop(s, signal)) return true }

        val timeoutMs = settings.state.value.toolTimeoutSeconds.coerceIn(5, 180) * 1000L
        val started = System.currentTimeMillis()
        val result = withTimeoutOrNull(timeoutMs) {
            tools.execute(s.taskId, action.copy(args = validated))
        } ?: com.kaori.lingtouch.tools.ActionResult(
            false,
            "单步工具执行超时（${timeoutMs}ms）",
            failure = FailureKind.UI_TIMEOUT,
            commit = ActionCommit.FAILED
        )
        val duration = System.currentTimeMillis() - started
        if (cancelled()) throw CancellationException("cancelled")
        awaitResume()

        val afterTree = AccessibilityBridge.snapshot()
        val afterScreen = ScreenState.from(
            afterTree,
            accessibility = AccessibilityBridge.isConnected(),
            ocr = settings.state.value.ocrEnabled
        )
        val screenChanged = afterScreen.signature.isNotBlank() &&
            afterScreen.signature != s.screen.signature
        s.screen = afterScreen
        refreshMemory(s, afterScreen.packageName)

        val commit = classifyCommit(action.name, result, screenChanged)
        val committedResult = result.copy(changed = screenChanged || result.changed, commit = commit)
        record(s, action.name, validated, committedResult, commit, result.message, duration)

        if (!result.ok) {
            val kind = result.failure ?: FailureKind.fromMessage(result.message)
            val info = FailureInfo(kind, result.message, s.stepIndex)
            s.failures.add(info)
            s.stepIndex++
            markCurrent(s, info)
            return recover(s, info)
        }

        s.executedOk++
        s.lastResult = summarizeResult(committedResult)
        verifySubGoal(s, afterTree, afterScreen)

        if (commit == ActionCommit.UNCHANGED && !isObservationAction(action.name)) {
            val info = FailureInfo(FailureKind.NO_UI_CHANGE, result.message, s.stepIndex)
            s.failures.add(info)
            val recovery = RecoveryPolicy.planFor(
                FailureKind.NO_UI_CHANGE,
                s.failures.count { it.kind == FailureKind.NO_UI_CHANGE }
            )
            if (recovery.waitMs > 0) delay(recovery.waitMs)
            s.failures.add(FailureInfo(FailureKind.NO_UI_CHANGE, recovery.hint, s.stepIndex))
            publish(
                status = AgentStatus.RECOVERING,
                message = "界面没有变化：${recovery.hint}",
                recovery = FailureKind.NO_UI_CHANGE.label
            )
        }

        loop.onScreen(afterScreen.signature)?.let { signal ->
            if (handleLoop(s, signal)) return true
        }
        s.stepIndex++
        return false
    }

    private fun verifySubGoal(s: Session, tree: UiTree?, screen: ScreenState) {
        val current = s.plan.current ?: return
        if (!current.verifiable) return
        val verification = Verifier.verify(current.expected(), tree, screen)
        if (!verification.verified) return
        s.plan = s.plan.updateCurrent(SubGoalStatus.SUCCEEDED)
        db.setTaskPlan(s.taskId, s.plan.toJson().toString())
        publish(
            plan = s.plan.progressText(),
            subGoals = s.plan.subGoals,
            message = "子目标完成：${current.title}"
        )
    }

    // ---------------------------------------------------------------- finish

    private suspend fun finishTask(s: Session, action: AgentAction): Boolean {
        val args = try {
            ToolRegistry.validate("finish_task", action.args)
        } catch (e: ActionValidationException) {
            val message = e.message ?: "参数错误"
            val info = FailureInfo(FailureKind.INVALID_ARGS, message, s.stepIndex)
            s.failures.add(info)
            record(
                s,
                "finish_task",
                action.args,
                com.kaori.lingtouch.tools.ActionResult(false, message, failure = FailureKind.INVALID_ARGS),
                ActionCommit.FAILED,
                message
            )
            s.stepIndex++
            return false
        }
        val expected = ExpectedState(
            packageName = args.str("expect_package"),
            activity = args.str("expect_activity"),
            text = args.str("expect_text"),
            textAbsent = args.bool("expect_text_absent"),
            checkedText = args.str("expect_checked"),
            source = "finish_task"
        )
        val tree = AccessibilityBridge.snapshot()
        val screen = ScreenState.from(
            tree,
            accessibility = AccessibilityBridge.isConnected(),
            ocr = settings.state.value.ocrEnabled
        )
        s.screen = screen
        val merged = Verifier.merge(expected, s.plan.current?.expected())
        val verification = Verifier.verify(merged, tree, screen)
        val gate = CompletionGate.evaluate(s.plan, verification, s.executedOk)
        when (gate.outcome) {
            GateOutcome.REJECTED -> {
                val kind = if (verification.status == VerificationStatus.VERIFIED_FAILED) {
                    FailureKind.NO_UI_CHANGE
                } else {
                    FailureKind.UNKNOWN
                }
                val info = FailureInfo(kind, gate.reason, s.stepIndex)
                s.failures.add(info)
                record(
                    s,
                    "finish_task",
                    args,
                    com.kaori.lingtouch.tools.ActionResult(false, gate.reason, failure = kind),
                    ActionCommit.FAILED,
                    gate.reason
                )
                s.lastResult = JSONObject()
                    .put("ok", false)
                    .put("message", gate.reason)
                    .put("verification", verification.status.name)
                publish(status = AgentStatus.RUNNING, message = "完成验证未通过：${gate.reason}")
                s.stepIndex++
                return false
            }
            GateOutcome.NEEDS_CONFIRMATION -> {
                val evidence = action.args.str("evidence").take(200)
                publish(status = AgentStatus.WAITING_USER, message = "任务完成无法本地验证，等待你确认")
                val accepted = confirmation.request(
                    "无法独立验证完成条件：${gate.reason}\n模型自述：${evidence.ifBlank { "（无）" }}",
                    Risk.MEDIUM,
                    confirmLabel = context.getString(R.string.task_confirm_finish),
                    denyLabel = context.getString(R.string.task_continue_execute)
                )
                if (cancelled()) throw CancellationException("cancelled")
                publish(status = AgentStatus.RUNNING)
                if (!accepted) {
                    val info = FailureInfo(
                        FailureKind.UNKNOWN,
                        "用户认为任务尚未完成：${gate.reason}",
                        s.stepIndex
                    )
                    s.failures.add(info)
                    record(
                        s,
                        "finish_task",
                        args,
                        com.kaori.lingtouch.tools.ActionResult(false, "用户未确认完成", failure = FailureKind.UNKNOWN),
                        ActionCommit.UNKNOWN,
                        info.message
                    )
                    s.stepIndex++
                    return false
                }
                s.unverified = true
            }
            GateOutcome.VERIFIED -> Unit
        }

        s.success = true
        val summaryText = args.str("summary").ifBlank { context.getString(R.string.task_done) }
        s.summary = buildString {
            append(Redactor.redactText(summaryText).take(500))
            if (s.unverified) append(context.getString(R.string.task_unverified_note))
            if (verification.verified) {
                append("\n").append(context.getString(R.string.task_verified_note, verification.detail))
            }
        }
        s.plan = s.plan.markOpen(
            if (s.unverified) SubGoalStatus.SKIPPED else SubGoalStatus.SUCCEEDED
        )
        db.setTaskPlan(s.taskId, s.plan.toJson().toString())
        record(
            s,
            "finish_task",
            args,
            com.kaori.lingtouch.tools.ActionResult(
                true,
                "任务完成",
                JSONObject().put("verification", verification.status.name)
            ),
            if (s.unverified) ActionCommit.UNKNOWN else ActionCommit.VERIFIED_SUCCESS,
            summaryText
        )
        publish(
            status = AgentStatus.RUNNING,
            message = "任务已完成",
            plan = s.plan.progressText(),
            subGoals = s.plan.subGoals
        )
        return true
    }

    // --------------------------------------------------------------- recover

    private suspend fun recover(s: Session, info: FailureInfo): Boolean {
        val attempt = s.failures.count { it.kind == info.kind }
        val recovery = RecoveryPolicy.planFor(info.kind, attempt)
        publish(
            status = AgentStatus.RECOVERING,
            message = "恢复中：${recovery.hint}",
            recovery = info.kind.label
        )
        if (recovery.waitMs > 0) delay(recovery.waitMs)
        when (recovery.action) {
            RecoveryAction.ABORT -> {
                markCurrentFailed(s, info)
                s.failure = info
                return true
            }
            RecoveryAction.ASK_USER -> {
                publish(status = AgentStatus.WAITING_USER, message = "需要你的决定：${info.message.take(160)}")
                val keepGoing = confirmation.request(
                    "${info.message}\n\n恢复建议：${recovery.hint}",
                    Risk.MEDIUM,
                    confirmLabel = context.getString(R.string.action_continue),
                    denyLabel = context.getString(R.string.action_stop)
                )
                if (cancelled()) throw CancellationException("cancelled")
                publish(status = AgentStatus.RUNNING)
                if (!keepGoing) {
                    s.failure = info
                    return true
                }
                s.failures.add(FailureInfo(info.kind, "用户选择继续：${recovery.hint}", s.stepIndex))
                loop.reset()
                return false
            }
            else -> {
                s.failures.add(FailureInfo(info.kind, recovery.hint, s.stepIndex))
                publish(status = AgentStatus.RUNNING, message = recovery.hint)
                return false
            }
        }
    }

    private suspend fun handleLoop(s: Session, signal: LoopSignal): Boolean {
        s.failures.add(FailureInfo(FailureKind.LOOP_DETECTED, signal.detail, s.stepIndex))
        s.loopWarnings++
        if (s.loopWarnings == 1) {
            publish(
                status = AgentStatus.RECOVERING,
                message = "检测到${signal.detail}，要求改变策略",
                recovery = FailureKind.LOOP_DETECTED.label
            )
            return false
        }
        publish(status = AgentStatus.WAITING_USER, message = "检测到循环：${signal.detail}")
        val keepGoing = confirmation.request(
            "${signal.detail}。是否继续尝试其他方案？",
            Risk.MEDIUM,
            confirmLabel = context.getString(R.string.action_continue),
            denyLabel = context.getString(R.string.action_stop)
        )
        if (cancelled()) throw CancellationException("cancelled")
        publish(status = AgentStatus.RUNNING)
        if (!keepGoing) {
            s.failure = FailureInfo(FailureKind.LOOP_DETECTED, signal.detail, s.stepIndex)
            return true
        }
        loop.reset()
        s.loopWarnings = 0
        return false
    }

    // --------------------------------------------------------------- helpers


    private fun classifyCommit(
        action: String,
        result: com.kaori.lingtouch.tools.ActionResult,
        screenChanged: Boolean
    ): ActionCommit {
        if (!result.ok) return ActionCommit.FAILED
        if (result.commit == ActionCommit.VERIFIED_SUCCESS) return ActionCommit.VERIFIED_SUCCESS
        if (action == "verify_state") return ActionCommit.VERIFIED_SUCCESS
        if (result.data.optBoolean("text_confirmed")) return ActionCommit.VERIFIED_SUCCESS
        if (isObservationAction(action)) return ActionCommit.DISPATCHED
        if (result.changed || result.data.optBoolean("changed") || screenChanged) {
            return ActionCommit.OBSERVED_CHANGED
        }
        return ActionCommit.UNCHANGED
    }

    private fun isObservationAction(action: String): Boolean {
        val group = ToolRegistry.find(action)?.group ?: return false
        return group == "observe" || group == "vision" || action.startsWith("get_") || action == "find_element"
    }

    private fun summarizeResult(result: com.kaori.lingtouch.tools.ActionResult): JSONObject {
        val data = JSONObject()
        val raw = result.data
        raw.optString("package").takeIf { it.isNotBlank() }?.let { data.put("package", it) }
        raw.optString("foreground").takeIf { it.isNotBlank() }?.let { data.put("foreground", it) }
        raw.optString("text").takeIf { it.isNotBlank() }?.let { data.put("text", Redactor.redactText(it).take(600)) }
        if (raw.has("text_found")) data.put("text_found", raw.optBoolean("text_found"))
        if (raw.has("checked")) data.put("checked", raw.optBoolean("checked"))
        if (raw.has("text_confirmed")) data.put("text_confirmed", raw.optBoolean("text_confirmed"))
        if (raw.has("allowed")) data.put("allowed", raw.optBoolean("allowed"))
        if (raw.has("changed")) data.put("changed", raw.optBoolean("changed"))
        raw.optJSONArray("matches")?.let { data.put("matches_count", it.length()) }
        raw.optJSONArray("apps")?.let { data.put("apps_count", it.length()) }
        return JSONObject()
            .put("ok", result.ok)
            .put("commit", result.commit.name)
            .put("message", Redactor.redactText(result.message).take(400))
            .put("data", data)
    }

    private fun record(
        s: Session,
        name: String,
        args: JSONObject,
        result: com.kaori.lingtouch.tools.ActionResult,
        commit: ActionCommit,
        message: String,
        duration: Long = 0
    ) {
        val safeArgs = Redactor.redactArgs(name, args)
        val shortArgs = ToolExecutor.shortArgs(safeArgs, 90)
        val safeMessage = Redactor.redactText(message)
        s.traces.add(
            StepTrace(
                index = s.stepIndex,
                action = name,
                args = shortArgs,
                ok = result.ok,
                commit = commit,
                message = safeMessage.take(200),
                failure = result.failure,
                screenChanged = result.changed
            )
        )
        val resultJson = JSONObject()
            .put("ok", result.ok)
            .put("commit", commit.name)
            .put("message", safeMessage.take(600))
            .put("failure", result.failure?.name ?: "")
            .put("changed", result.changed)
            .put("duration_ms", duration)
        runCatching {
            db.addStep(
                TaskStepEntity(
                    taskId = s.taskId,
                    stepIndex = s.stepIndex,
                    action = name,
                    argsJson = safeArgs.toString(),
                    resultJson = resultJson.toString(),
                    ok = result.ok,
                    durationMs = duration
                )
            )
        }
        state.update {
            it.copy(
                steps = it.steps + AgentStepUi(
                    index = s.stepIndex + 1,
                    action = name,
                    detail = shortArgs,
                    ok = result.ok,
                    message = safeMessage.take(200),
                    commit = commit
                ),
                currentStep = s.stepIndex + 1,
                message = safeMessage.take(200)
            )
        }
    }

    private fun applyPlan(s: Session, planArray: org.json.JSONArray) {
        val parsed = TaskPlan.parse(s.goal, JSONObject().put("plan", planArray)) ?: return
        s.plan = s.plan.withSubGoals(parsed.subGoals, "第 ${s.stepIndex + 1} 步由模型重新规划")
        db.setTaskPlan(s.taskId, s.plan.toJson().toString())
        publish(
            plan = s.plan.progressText(),
            subGoals = s.plan.subGoals,
            message = "已更新执行计划"
        )
    }

    private fun refreshMemory(s: Session, packageName: String) {
        if (packageName.isBlank() || packageName == s.memoryPackage) return
        s.memoryPackage = packageName
        s.appVersion = apps.findByPackage(packageName)?.versionName
        s.memoryContext = memory.contextFor(s.goal, packageName, s.appVersion)
    }

    private suspend fun awaitResume() {
        while (paused() && !cancelled()) delay(150)
    }

    private fun publish(
        status: AgentStatus? = null,
        message: String? = null,
        plan: String? = null,
        subGoals: List<SubGoal>? = null,
        screen: String? = null,
        recovery: String? = null,
        reasoning: String? = null
    ) {
        state.update { current ->
            current.copy(
                status = status ?: current.status,
                message = message ?: current.message,
                plan = plan ?: current.plan,
                subGoals = subGoals ?: current.subGoals,
                screen = screen ?: current.screen,
                recovery = recovery ?: current.recovery,
                reasoning = reasoning ?: current.reasoning
            )
        }
    }

    private fun publishBudget(s: Session) {
        state.update {
            it.copy(
                apiRequests = s.budget.apiRequests,
                promptTokens = s.budget.promptTokens,
                completionTokens = s.budget.completionTokens,
                estimatedTokens = s.budget.estimatedTokens,
                exactUsage = s.budget.exactUsage
            )
        }
    }

    private fun markCurrent(s: Session, info: FailureInfo) {
        val current = s.plan.current ?: return
        s.plan = s.plan.updateCurrent(
            SubGoalStatus.RUNNING,
            reason = info.message.take(120),
            recovery = RecoveryPolicy.planFor(info.kind, 1).hint
        )
        db.setTaskPlan(s.taskId, s.plan.toJson().toString())
        publish(
            plan = s.plan.progressText(),
            subGoals = s.plan.subGoals,
            message = "子目标受挫：${current.title}"
        )
    }

    private fun markCurrentFailed(s: Session, info: FailureInfo) {
        val current = s.plan.current ?: return
        s.plan = s.plan.updateCurrent(
            SubGoalStatus.FAILED,
            reason = info.message.take(120),
            recovery = RecoveryPolicy.planFor(info.kind, 1).hint
        )
        db.setTaskPlan(s.taskId, s.plan.toJson().toString())
        publish(
            plan = s.plan.progressText(),
            subGoals = s.plan.subGoals,
            message = "子目标失败：${current.title}"
        )
    }

    private fun describeAction(name: String, args: JSONObject): String {
        val text = Redactor.redactJson(args.toString())
        val detail = if (text.length <= 180) text else text.take(180) + "…"
        return "即将执行 $name $detail"
    }

    private fun describeFailure(info: FailureInfo): String {
        val suggestion = RecoveryPolicy.planFor(info.kind, 1).hint
        return "${info.kind.label}：${Redactor.redactText(info.message).take(200)}\n建议：$suggestion"
    }

    private suspend fun finalize(s: Session) = withContext(NonCancellable) {
        val wasCancelled = cancelled()
        val status = when {
            wasCancelled -> "CANCELLED"
            s.success -> "SUCCESS"
            else -> "FAILED"
        }
        val failureMessage = s.failure?.let { describeFailure(it) }
        val finalMessage = when {
            wasCancelled -> context.getString(R.string.task_cancelled)
            s.success -> s.summary ?: context.getString(R.string.task_done)
            else -> failureMessage ?: context.getString(R.string.task_failed)
        }
        runCatching { db.finishTask(s.taskId, status, finalMessage, s.success, s.traces.size) }
        runCatching {
            val steps = db.steps(s.taskId)
            if (steps.isNotEmpty() && !wasCancelled) {
                memory.learnFromTask(
                    taskId = s.taskId,
                    goal = s.goal,
                    steps = steps,
                    success = s.success,
                    summary = finalMessage,
                    planText = s.plan.progressText(),
                    failureKind = s.failure?.kind?.name,
                    recoveryHint = s.failure?.let { RecoveryPolicy.planFor(it.kind, 1).hint },
                    appVersion = s.appVersion,
                    screenSignature = s.screen.signature
                )
            }
            memory.recordUsage(s.memoryContext, s.success)
        }.onFailure { Logx.w("Agent", "记忆写入失败：${it.message}") }
        if (settings.state.value.autoCleanup) {
            runCatching { temp.cleanupTask(s.taskId) }
        }
        state.update {
            it.copy(
                status = when {
                    wasCancelled -> AgentStatus.CANCELLED
                    s.success -> AgentStatus.SUCCEEDED
                    else -> AgentStatus.FAILED
                },
                result = finalMessage,
                error = if (!s.success && !wasCancelled) finalMessage else null,
                message = finalMessage,
                plan = s.plan.progressText(),
                subGoals = s.plan.subGoals,
                screen = s.screen.label,
                recovery = "",
                finishedAt = System.currentTimeMillis(),
                pendingRequest = null
            )
        }
        onFinished(s.taskId)
    }

    private inner class Session(val taskId: Long, val goal: String) {
        var plan: TaskPlan = TaskPlan.single(goal, Constraints.fromGoal(goal))
        val traces = mutableListOf<StepTrace>()
        val failures = mutableListOf<FailureInfo>()
        var budget = TaskBudget()
        var stepIndex = 0
        var executedOk = 0
        var formatErrors = 0
        var loopWarnings = 0
        var networkRetries = 0
        var screen: ScreenState = ScreenState.empty()
        var memoryPackage: String = ""
        var appVersion: String? = null
        var memoryContext: MemoryContext = MemoryContext()
        var lastResult: JSONObject? = null
        val reasoningLog = StringBuilder()
        var modeMemory: String = ""
        var success = false
        var unverified = false
        var summary: String? = null
        var failure: FailureInfo? = null
    }
}
