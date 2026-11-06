package com.kaori.lingtouch.agent

/** Where a single action stopped: dispatching it is never the same as achieving the goal. */
enum class ActionCommit {
    DISPATCHED,
    OBSERVED_CHANGED,
    VERIFIED_SUCCESS,
    UNCHANGED,
    FAILED,
    UNKNOWN
}

/** Structured failure taxonomy, used by recovery, loop protection and memory. */
enum class FailureKind {
    TARGET_NOT_FOUND,
    STALE_NODE,
    NO_UI_CHANGE,
    UI_TIMEOUT,
    APP_NOT_FOREGROUND,
    PERMISSION_DENIED,
    VISION_UNAVAILABLE,
    MODEL_FORMAT_ERROR,
    NETWORK_ERROR,
    RATE_LIMIT,
    CANCELLED,
    LOOP_DETECTED,
    INVALID_ARGS,
    HIGH_RISK_DENIED,
    UNKNOWN;

    val label: String
        get() = when (this) {
            TARGET_NOT_FOUND -> "未找到目标控件"
            STALE_NODE -> "控件索引已失效"
            NO_UI_CHANGE -> "界面无变化"
            UI_TIMEOUT -> "界面响应超时"
            APP_NOT_FOREGROUND -> "目标应用不在前台"
            PERMISSION_DENIED -> "权限或服务不可用"
            VISION_UNAVAILABLE -> "截图/OCR 不可用"
            MODEL_FORMAT_ERROR -> "模型输出格式错误"
            NETWORK_ERROR -> "网络错误"
            RATE_LIMIT -> "请求被限流"
            CANCELLED -> "已取消"
            LOOP_DETECTED -> "检测到无效循环"
            INVALID_ARGS -> "动作参数无效"
            HIGH_RISK_DENIED -> "用户拒绝高风险操作"
            UNKNOWN -> "未知错误"
        }

    companion object {
        /** Best-effort classification for text coming from a tool or a legacy code path. */
        fun fromMessage(message: String): FailureKind {
            val text = message.lowercase()
            return when {
                text.contains("失效") || text.contains("stale") -> STALE_NODE
                text.contains("未找到") || text.contains("没有找到") ||
                    text.contains("not found") || text.contains("无匹配") -> TARGET_NOT_FOUND
                text.contains("无变化") || text.contains("没有变化") -> NO_UI_CHANGE
                text.contains("超时") || text.contains("timeout") -> UI_TIMEOUT
                text.contains("前台") || text.contains("foreground") -> APP_NOT_FOREGROUND
                text.contains("未连接") || text.contains("未启用") ||
                    text.contains("permission") || text.contains("拒绝") -> PERMISSION_DENIED
                text.contains("截图") || text.contains("ocr") || text.contains("识别") -> VISION_UNAVAILABLE
                text.contains("网络") || text.contains("http") || text.contains("连接失败") -> NETWORK_ERROR
                text.contains("限流") || text.contains("429") -> RATE_LIMIT
                text.contains("参数") || text.contains("缺少") || text.contains("无效") -> INVALID_ARGS
                else -> UNKNOWN
            }
        }
    }
}

data class FailureInfo(
    val kind: FailureKind,
    val message: String,
    val step: Int = 0
) {
    fun line(): String = "[$step] ${kind.name}：$message"
}

/** Request/token accounting. Exact numbers are only shown when the provider reports usage. */
data class TaskBudget(
    val apiRequests: Int = 0,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val estimatedTokens: Int = 0,
    val exactUsage: Boolean = false
) {
    val totalTokens: Int get() = promptTokens + completionTokens

    fun request(): TaskBudget = copy(apiRequests = apiRequests + 1)

    fun usage(prompt: Int, completion: Int): TaskBudget =
        if (prompt <= 0 && completion <= 0) {
            this
        } else {
            copy(
                promptTokens = promptTokens + prompt,
                completionTokens = completionTokens + completion,
                exactUsage = true
            )
        }

    fun estimate(tokens: Int): TaskBudget = copy(estimatedTokens = maxOf(estimatedTokens, tokens))

    fun displayText(): String = buildString {
        append("API $apiRequests 次")
        when {
            exactUsage && totalTokens > 0 -> append(" · Token $totalTokens（真实）")
            estimatedTokens > 0 -> append(" · Token ≈$estimatedTokens（估算）")
            else -> append(" · Token 未统计")
        }
    }
}

/** One executed step, compact enough for the model context. */
data class StepTrace(
    val index: Int,
    val action: String,
    val args: String,
    val ok: Boolean,
    val commit: ActionCommit,
    val message: String,
    val failure: FailureKind? = null,
    val screenChanged: Boolean = false
) {
    fun line(messageMax: Int = 120): String = buildString {
        append(index + 1).append(". ").append(action)
        if (args.isNotBlank()) append("(").append(args.take(80)).append(")")
        append(" → ")
        append(
            when {
                !ok -> "失败"
                commit == ActionCommit.VERIFIED_SUCCESS -> "已验证成功"
                commit == ActionCommit.OBSERVED_CHANGED -> "已提交并观察到变化"
                commit == ActionCommit.UNCHANGED -> "已提交但界面无变化"
                commit == ActionCommit.DISPATCHED -> "已提交"
                else -> commit.name
            }
        )
        if (failure != null) append(" [").append(failure.name).append("]")
        if (message.isNotBlank()) append("：").append(message.take(messageMax))
    }
}
