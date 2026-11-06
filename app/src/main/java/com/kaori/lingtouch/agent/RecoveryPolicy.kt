package com.kaori.lingtouch.agent

enum class RecoveryAction {
    RETRY_WITH_FEEDBACK,
    WAIT_AND_RETRY,
    REOBSERVE,
    ASK_USER,
    REPLAN,
    RELAUNCH,
    ABORT
}

data class RecoveryPlan(val action: RecoveryAction, val hint: String, val waitMs: Long = 0)

/**
 * Maps a structured failure to a bounded recovery strategy. Hints are fed back to the
 * model as structured failure notes instead of silently repeating the same action.
 */
object RecoveryPolicy {
    fun planFor(kind: FailureKind, attempt: Int): RecoveryPlan = when (kind) {
        FailureKind.TARGET_NOT_FOUND -> when {
            attempt <= 1 -> RecoveryPlan(
                RecoveryAction.REOBSERVE,
                "重新读取界面后再定位，禁止复用旧 nodeId；可改用 resourceId / 文字 / desc 匹配",
                300
            )
            attempt == 2 -> RecoveryPlan(
                RecoveryAction.RETRY_WITH_FEEDBACK,
                "换一种定位方式；如果是列表目标，可先有限滚动查找，再检查是否被弹窗遮挡",
                0
            )
            else -> RecoveryPlan(RecoveryAction.ASK_USER, "多次仍未找到目标控件，请确认目标是否在当前界面")
        }
        FailureKind.STALE_NODE -> RecoveryPlan(
            RecoveryAction.REOBSERVE,
            "界面已变化，nodeId 全部失效；必须重新读取 UI 树后再操作",
            200
        )
        FailureKind.NO_UI_CHANGE -> when {
            attempt <= 2 -> RecoveryPlan(
                RecoveryAction.WAIT_AND_RETRY,
                "上一次操作没有引起界面变化：先等待加载，或检查点击是否落在可点击区域",
                900
            )
            else -> RecoveryPlan(
                RecoveryAction.ASK_USER,
                "连续多次操作后界面仍无变化，请检查是否有弹窗、键盘遮挡或应用无响应"
            )
        }
        FailureKind.UI_TIMEOUT -> RecoveryPlan(
            RecoveryAction.WAIT_AND_RETRY,
            "界面在超时时间内没有就绪：等待后重试，或改用 wait_for_ui 检测关键文字",
            1000
        )
        FailureKind.APP_NOT_FOREGROUND -> RecoveryPlan(
            RecoveryAction.RELAUNCH,
            "目标应用不在前台：重新启动应用或返回到目标应用后再继续",
            400
        )
        FailureKind.PERMISSION_DENIED -> RecoveryPlan(
            RecoveryAction.ASK_USER,
            "无障碍/Shizuku/截图权限不可用：请重新授权后继续，不要假装授权成功"
        )
        FailureKind.VISION_UNAVAILABLE -> RecoveryPlan(
            RecoveryAction.RETRY_WITH_FEEDBACK,
            "截图或 OCR 不可用：改用 UI 树中的文字和控件完成操作",
            0
        )
        FailureKind.MODEL_FORMAT_ERROR -> RecoveryPlan(
            RecoveryAction.RETRY_WITH_FEEDBACK,
            "输出必须是单个 JSON 对象，字段为 action/args/thought/expect，禁止 Markdown 和多余文本",
            0
        )
        FailureKind.NETWORK_ERROR -> RecoveryPlan(
            RecoveryAction.WAIT_AND_RETRY,
            "网络请求失败：按退避等待后重试，不要伪造执行结果",
            1500
        )
        FailureKind.RATE_LIMIT -> RecoveryPlan(
            RecoveryAction.WAIT_AND_RETRY,
            "接口限流：暂停并退避后重试，避免继续高频请求",
            3000
        )
        FailureKind.INVALID_ARGS -> RecoveryPlan(
            RecoveryAction.RETRY_WITH_FEEDBACK,
            "参数未通过 Schema 校验：按工具签名修正后重新提交",
            0
        )
        FailureKind.HIGH_RISK_DENIED -> RecoveryPlan(
            RecoveryAction.ABORT,
            "用户已拒绝高风险操作，不得绕过或改用其他路径执行同一操作"
        )
        FailureKind.CANCELLED -> RecoveryPlan(RecoveryAction.ABORT, "任务已取消")
        FailureKind.LOOP_DETECTED -> RecoveryPlan(
            RecoveryAction.ASK_USER,
            "检测到无进展循环：停止重复动作，等待用户决定是否继续"
        )
        FailureKind.UNKNOWN -> when {
            attempt <= 1 -> RecoveryPlan(RecoveryAction.RETRY_WITH_FEEDBACK, "分析失败原因后改变策略", 300)
            else -> RecoveryPlan(RecoveryAction.ASK_USER, "多次失败且原因不明确，请用户确认下一步")
        }
    }
}
