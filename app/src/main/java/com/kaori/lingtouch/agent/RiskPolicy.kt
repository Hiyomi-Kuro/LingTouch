package com.kaori.lingtouch.agent

import com.kaori.lingtouch.tools.Risk
import org.json.JSONObject

/** Conservative client-side risk classification, independent of what the model claims. */
object RiskPolicy {
    private val highRiskWords = listOf(
        "删除", "清空", "卸载", "安装", "支付", "付款", "转账", "购买", "下单",
        "发送", "发布", "分享", "密码", "注销", "退出登录", "恢复出厂", "格式化",
        "授权", "解除绑定", "解绑", "充值", "提现", "delete", "uninstall", "pay", "transfer", "send"
    )

    private val safeActions = setOf(
        "get_installed_apps", "get_activity_info", "get_current_app", "get_ui_tree",
        "get_screen_text", "find_element", "verify_state", "wait_for_ui",
        "capture_screen", "run_ocr", "press_back", "press_home", "open_recents",
        "finish_task", "fail_task", "request_user_confirmation"
    )

    fun assess(action: String, args: JSONObject): Risk {
        if (action in safeActions) return Risk.LOW
        val blob = args.toString()
        return if (highRiskWords.any { blob.contains(it, ignoreCase = true) }) Risk.HIGH else Risk.LOW
    }

    fun max(a: Risk, b: Risk): Risk = if (a.level >= b.level) a else b
}
