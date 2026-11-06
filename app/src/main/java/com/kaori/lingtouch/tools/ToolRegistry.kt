package com.kaori.lingtouch.tools

import org.json.JSONObject

/**
 * The only actions the model may request. Every call is validated against this schema
 * before execution; free-form device commands are impossible by construction.
 */
object ToolRegistry {

    private fun str(name: String, required: Boolean = false, desc: String = "") =
        ToolArg(name, ArgType.STRING, required, desc)

    private fun int(name: String, required: Boolean = false, desc: String = "", min: Int = Int.MIN_VALUE, max: Int = Int.MAX_VALUE) =
        ToolArg(name, ArgType.INT, required, desc, min = min, max = max)

    private fun bool(name: String, required: Boolean = false, desc: String = "") =
        ToolArg(name, ArgType.BOOL, required, desc)

    private fun enum(name: String, values: List<String>, required: Boolean = true, desc: String = "") =
        ToolArg(name, ArgType.ENUM, required, desc, values = values)

    val specs: List<ToolSpec> = listOf(
        ToolSpec(
            "launch_app", "启动已安装的应用（按包名或应用名匹配）",
            listOf(str("package", desc = "包名"), str("app", desc = "应用名称")),
            Risk.LOW, "app", requiresOneOf = listOf("package", "app")
        ),
        ToolSpec(
            "launch_activity", "启动指定应用内的 Activity",
            listOf(str("package", true, "包名"), str("activity", true, "完整类名，例如 .Settings")),
            Risk.MEDIUM, "app"
        ),
        ToolSpec(
            "get_installed_apps", "查询已安装应用列表",
            listOf(str("query", desc = "名称或包名过滤"), bool("system_only", desc = "只看系统应用"), int("limit", desc = "返回数量上限", min = 1, max = 200)),
            Risk.LOW, "app"
        ),
        ToolSpec(
            "get_activity_info", "查询应用的 Activity、导出状态与当前前台 Activity",
            listOf(str("package", true, "包名")),
            Risk.LOW, "app"
        ),
        ToolSpec("get_current_app", "获取当前前台应用与界面标题", emptyList(), Risk.LOW, "observe"),
        ToolSpec(
            "get_ui_tree", "读取当前界面的无障碍节点树",
            listOf(int("limit", desc = "节点数量上限", min = 1, max = 300)),
            Risk.LOW, "observe"
        ),
        ToolSpec("get_screen_text", "提取当前界面所有可访问文字", emptyList(), Risk.LOW, "observe"),
        ToolSpec("capture_screen", "截取当前屏幕（保存到任务私有缓存）", emptyList(), Risk.LOW, "vision"),
        ToolSpec("run_ocr", "对当前屏幕执行端侧 OCR，返回文字、bounds 与屏幕坐标 center（可直接作为 tap 的 x/y）", emptyList(), Risk.LOW, "vision"),
        ToolSpec(
            "tap", "点击控件。优先 nodeId/text/resourceId；图标、游戏、Unity、无文字画布用 OCR center 或截图坐标 x/y",
            listOf(int("nodeId", desc = "get_ui_tree 返回的 index", min = 0), str("text", desc = "控件文字"), str("resourceId", desc = "资源 ID（可省略包名前缀）"), str("desc", desc = "内容描述"), int("x", desc = "屏幕 x 坐标", min = 0), int("y", desc = "屏幕 y 坐标", min = 0)),
            Risk.LOW, "act"
        ),
        ToolSpec(
            "long_press", "长按控件",
            listOf(int("nodeId", desc = "节点 index", min = 0), str("text"), str("resourceId"), int("x", min = 0), int("y", min = 0)),
            Risk.LOW, "act"
        ),
        ToolSpec(
            "swipe", "在屏幕上滑动",
            listOf(int("x1", true, min = 0), int("y1", true, min = 0), int("x2", true, min = 0), int("y2", true, min = 0), int("durationMs", desc = "滑动时长", min = 50, max = 3000)),
            Risk.LOW, "act"
        ),
        ToolSpec(
            "scroll", "滚动当前可滚动区域。up=查看更早内容，down=查看更多内容",
            listOf(enum("direction", listOf("up", "down", "left", "right")), int("nodeId", desc = "指定滚动容器", min = 0)),
            Risk.LOW, "act"
        ),
        ToolSpec(
            "input_text", "向输入框输入文字（先点击聚焦输入框）",
            listOf(str("text", true, "要输入的文字"), int("nodeId", desc = "输入框 index", min = 0), str("resourceId"), str("desc")),
            Risk.MEDIUM, "act"
        ),
        ToolSpec("press_back", "返回上一页", emptyList(), Risk.LOW, "system"),
        ToolSpec("press_home", "回到桌面", emptyList(), Risk.LOW, "system"),
        ToolSpec("open_recents", "打开最近任务", emptyList(), Risk.LOW, "system"),
        ToolSpec(
            "wait_for_ui", "等待指定文字或应用出现在界面上",
            listOf(str("text", desc = "等待的文字"), str("package", desc = "等待的应用包名"), int("timeoutMs", desc = "超时毫秒", min = 200, max = 30000)),
            Risk.LOW, "observe"
        ),
        ToolSpec(
            "find_element", "在界面中查找控件，返回匹配项与坐标",
            listOf(str("text"), str("resourceId"), str("desc"), bool("clickable", desc = "只看可点击控件")),
            Risk.LOW, "observe"
        ),
        ToolSpec(
            "verify_state", "验证界面状态（文字是否存在、前台应用是否正确）",
            listOf(str("text", desc = "要检查的文字"), str("package", desc = "期望的前台包名"), bool("expect_present", desc = "true 表示应存在，false 表示应不存在"), str("checked_text", desc = "应处于勾选状态的文字")),
            Risk.LOW, "observe"
        ),
        ToolSpec(
            "request_user_confirmation", "请求用户确认高风险操作",
            listOf(str("reason", true, "需要确认的原因")),
            Risk.LOW, "control"
        ),
        ToolSpec(
            "finish_task",
            "任务目标已达成时结束任务。必须提供可被本地独立校验的完成条件，模型自述的 evidence 不算证据",
            listOf(
                str("summary", true, "给用户的最终结果"),
                str("evidence", desc = "模型自述的完成依据，仅作说明，不作为系统证据"),
                str("expect_text", desc = "完成时应存在于界面的文字"),
                str("expect_package", desc = "完成时应处于前台的应用包名"),
                str("expect_activity", desc = "完成时应处于的 Activity"),
                str("expect_checked", desc = "完成时应处于选中状态的开关/选项文字"),
                bool("expect_text_absent", desc = "true 表示 expect_text 应已从界面消失")
            ),
            Risk.LOW, "control"
        ),
        ToolSpec(
            "fail_task", "确认任务无法完成时结束任务",
            listOf(str("reason", true, "失败原因"), str("suggestion", desc = "给用户的建议")),
            Risk.LOW, "control"
        )
    )

    fun find(name: String): ToolSpec? = specs.firstOrNull { it.name == name }

    fun prompt(): String = specs.joinToString("\n") { spec ->
        val args = spec.args.joinToString(", ") { arg ->
            val opt = if (arg.required) "" else "?"
            val type = when (arg.type) {
                ArgType.STRING -> "string"
                ArgType.INT -> "int"
                ArgType.BOOL -> "bool"
                ArgType.ENUM -> arg.values.joinToString("|")
            }
            "${arg.name}$opt:$type"
        }
        "- ${spec.name}($args) — ${spec.description}"
    }

    fun validate(name: String, rawArgs: JSONObject): JSONObject {
        val spec = find(name) ?: throw ActionValidationException(
            "未知操作 \"$name\"。可用操作：${specs.joinToString(", ") { it.name }}"
        )
        val out = JSONObject()
        spec.args.forEach { arg ->
            val present = rawArgs.has(arg.name) && !rawArgs.isNull(arg.name)
            if (!present) {
                if (arg.required) throw ActionValidationException("操作 $name 缺少必需参数 ${arg.name}")
                return@forEach
            }
            when (arg.type) {
                ArgType.STRING -> {
                    val value = rawArgs.opt(arg.name)?.toString().orEmpty()
                    if (value.length > 4000) throw ActionValidationException("参数 ${arg.name} 过长")
                    out.put(arg.name, value)
                }
                ArgType.INT -> {
                    val value = when (val raw = rawArgs.opt(arg.name)) {
                        is Number -> raw.toInt()
                        is String -> raw.trim().toIntOrNull()
                            ?: throw ActionValidationException("参数 ${arg.name} 必须是整数")
                        else -> throw ActionValidationException("参数 ${arg.name} 必须是整数")
                    }
                    if (value < arg.min || value > arg.max) {
                        throw ActionValidationException("参数 ${arg.name} 超出范围 [${arg.min}, ${arg.max}]")
                    }
                    out.put(arg.name, value)
                }
                ArgType.BOOL -> {
                    val raw = rawArgs.opt(arg.name)
                    val value = when (raw) {
                        is Boolean -> raw
                        is String -> raw.equals("true", true) || raw == "1"
                        is Number -> raw.toInt() != 0
                        else -> throw ActionValidationException("参数 ${arg.name} 必须是布尔值")
                    }
                    out.put(arg.name, value)
                }
                ArgType.ENUM -> {
                    val value = rawArgs.opt(arg.name)?.toString()?.lowercase().orEmpty()
                    if (value !in arg.values) {
                        throw ActionValidationException("参数 ${arg.name} 必须是 ${arg.values.joinToString("/")} 之一")
                    }
                    out.put(arg.name, value)
                }
            }
        }
        if (spec.requiresOneOf.isNotEmpty() && spec.requiresOneOf.none { out.has(it) && out.optString(it).isNotBlank() }) {
            throw ActionValidationException("操作 $name 至少需要参数之一：${spec.requiresOneOf.joinToString(", ")}")
        }
        return out
    }
}
