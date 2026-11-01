package com.kaori.lingtouch.tools

import android.content.Context
import com.kaori.lingtouch.accessibility.AccessibilityBridge
import com.kaori.lingtouch.accessibility.NodeMatcher
import com.kaori.lingtouch.agent.ConfirmationBroker
import com.kaori.lingtouch.apps.AppRepository
import com.kaori.lingtouch.ocr.OcrEngine
import com.kaori.lingtouch.settings.SettingsRepository
import com.kaori.lingtouch.shizuku.ShizukuManager
import com.kaori.lingtouch.storage.TempResourceManager
import com.kaori.lingtouch.util.int
import com.kaori.lingtouch.util.str
import com.kaori.lingtouch.util.bool
import com.kaori.lingtouch.util.Logx
import com.kaori.lingtouch.vision.ScreenCaptureManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/**
 * Executes validated actions against real device APIs. Every tool reports what actually
 * happened; nothing returns success unless the underlying API call succeeded.
 */
class ToolExecutor(
    private val context: Context,
    private val apps: AppRepository,
    private val shizuku: ShizukuManager,
    private val ocr: OcrEngine,
    private val capture: ScreenCaptureManager,
    private val temp: TempResourceManager,
    private val settings: SettingsRepository,
    private val confirmation: ConfirmationBroker
) {

    suspend fun execute(taskId: Long, action: AgentAction): ActionResult {
        return try {
            when (action.name) {
                "launch_app" -> launchApp(action.args)
                "launch_activity" -> launchActivity(action.args)
                "get_installed_apps" -> installedApps(action.args)
                "get_activity_info" -> activityInfo(action.args)
                "get_current_app" -> currentApp()
                "get_ui_tree" -> uiTree(action.args)
                "get_screen_text" -> screenText()
                "capture_screen" -> captureScreen(taskId)
                "run_ocr" -> runOcr(taskId)
                "tap" -> tap(action.args)
                "long_press" -> longPress(action.args)
                "swipe" -> swipe(action.args)
                "scroll" -> scroll(action.args)
                "input_text" -> inputText(action.args)
                "press_back" -> global("返回", 0)
                "press_home" -> global("回到桌面", 1)
                "open_recents" -> global("打开最近任务", 2)
                "wait_for_ui" -> waitForUi(action.args)
                "find_element" -> findElement(action.args)
                "verify_state" -> verifyState(action.args)
                "request_user_confirmation" -> askConfirmation(action.args)
                "finish_task", "fail_task" -> ActionResult(
                    true, action.name, JSONObject().put("handled_by_agent", true)
                )
                else -> ActionResult(false, "未实现的操作 ${action.name}")
            }
        } catch (e: ActionValidationException) {
            ActionResult(false, e.message ?: "参数无效")
        } catch (t: Throwable) {
            Logx.e("Tool", "执行 ${action.name} 异常", t)
            ActionResult(false, "执行异常：${t.message ?: t.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------- launchers

    private suspend fun launchApp(args: JSONObject): ActionResult {
        if (apps.cached().isEmpty()) apps.scan()
        val query = args.str("package").ifBlank { args.str("app") }
        val outcome = apps.launchApp(query)
        if (!outcome.success) return ActionResult(false, outcome.message, retryable = true)
        kotlinx.coroutines.delay(700)
        val foreground = AccessibilityBridge.foregroundPackage()
        return ActionResult(
            true, outcome.message,
            JSONObject().put("component", outcome.component ?: "").put("foreground", foreground)
        )
    }

    private suspend fun launchActivity(args: JSONObject): ActionResult {
        if (apps.cached().isEmpty()) apps.scan()
        val pkg = args.str("package")
        var cls = args.str("activity")
        if (cls.startsWith(".")) cls = pkg + cls
        if (pkg.isBlank() || cls.isBlank()) return ActionResult(false, "需要 package 与 activity")
        val outcome = apps.launchComponent(pkg, cls)
        if (outcome.success) {
            kotlinx.coroutines.delay(700)
            return ActionResult(
                true, outcome.message,
                JSONObject().put("component", "$pkg/$cls")
                    .put("foreground", AccessibilityBridge.foregroundPackage())
            )
        }
        // Shizuku fallback (shell `am start`), still a whitelisted command.
        if (shizuku.state.value.permission) {
            val shell = shizuku.startActivity(pkg, cls)
            return if (shell.ok) {
                kotlinx.coroutines.delay(900)
                ActionResult(
                    true, "已通过 Shizuku 启动（${shell.output.trim().take(120)}）",
                    JSONObject().put("component", "$pkg/$cls").put("via", "shizuku")
                )
            } else {
                ActionResult(false, "${outcome.message}；Shizuku 也失败：${shell.error ?: shell.output.take(160)}", retryable = true)
            }
        }
        return ActionResult(false, outcome.message, retryable = true)
    }

    private suspend fun installedApps(args: JSONObject): ActionResult {
        if (apps.cached().isEmpty()) apps.scan()
        val query = args.str("query")
        val systemOnly = args.bool("system_only")
        val limit = args.int("limit", 40).let { if (it <= 0) 40 else it }
        val filtered = apps.cached().asSequence()
            .filter { !systemOnly || it.isSystem }
            .filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
            .take(limit)
            .toList()
        val array = JSONArray()
        filtered.forEach {
            array.put(
                JSONObject().put("package", it.packageName).put("label", it.label)
                    .put("system", it.isSystem).put("version", it.versionName ?: "")
            )
        }
        return ActionResult(
            true, "找到 ${filtered.size} 个应用",
            JSONObject().put("apps", array).put("total", apps.cached().size)
        )
    }

    private suspend fun activityInfo(args: JSONObject): ActionResult {
        val pkg = args.str("package")
        if (pkg.isBlank()) return ActionResult(false, "需要 package")
        val activities = apps.activities(pkg)
        val array = JSONArray()
        activities.forEach {
            array.put(
                JSONObject().put("class", it.className).put("label", it.label)
                    .put("exported", it.exported).put("launcher", it.launcher)
                    .put("permission", it.permission ?: "")
            )
        }
        val data = JSONObject()
            .put("package", pkg)
            .put("foreground_package", AccessibilityBridge.foregroundPackage())
            .put("foreground_activity", com.kaori.lingtouch.accessibility.ForegroundState.activityName)
            .put("activities", array)
        return ActionResult(true, "发现 ${activities.size} 个 Activity", data)
    }

    private suspend fun currentApp(): ActionResult {
        val tree = AccessibilityBridge.snapshot()
        val fg = tree?.packageName?.takeIf { it.isNotBlank() } ?: AccessibilityBridge.foregroundPackage()
        val label = apps.findByPackage(fg)?.label ?: fg
        val data = JSONObject()
            .put("package", fg)
            .put("label", label)
            .put("activity", tree?.activityName ?: com.kaori.lingtouch.accessibility.ForegroundState.activityName)
            .put("screen", JSONObject().put("width", tree?.width ?: 0).put("height", tree?.height ?: 0))
        return ActionResult(true, "前台应用：$label", data)
    }

    // -------------------------------------------------------------- observe

    private suspend fun uiTree(args: JSONObject): ActionResult {
        val tree = AccessibilityBridge.snapshot()
            ?: return ActionResult(false, "无障碍服务未连接，无法读取界面", retryable = true)
        val limit = args.int("limit", 120).let { if (it <= 0) 120 else it }
        val data = tree.toJson()
        data.put("prompt", tree.prompt(limit))
        return ActionResult(
            true, "读取到 ${tree.nodes.size} 个可见节点（${tree.packageName}）", data
        )
    }

    private suspend fun screenText(): ActionResult {
        val tree = AccessibilityBridge.snapshot()
            ?: return ActionResult(false, "无障碍服务未连接", retryable = true)
        val text = tree.screenText()
        return ActionResult(
            true, if (text.isBlank()) "当前界面没有可访问文字" else "已提取界面文字",
            JSONObject().put("text", text.take(6000)).put("package", tree.packageName)
                .put("activity", tree.activityName)
        )
    }

    private suspend fun captureScreen(taskId: Long): ActionResult {
        val result = capture.capture(taskId)
        val value = result.getOrElse {
            return ActionResult(false, "截图失败：${it.message}", retryable = true)
        }
        val data = JSONObject()
            .put("source", value.source)
            .put("width", value.bitmap.width)
            .put("height", value.bitmap.height)
            .put("path", value.file?.absolutePath ?: "")
            .put("bytes", value.file?.length() ?: 0)
        value.bitmap.recycle()
        return ActionResult(true, "已截图（${value.source}）", data)
    }

    private suspend fun runOcr(taskId: Long): ActionResult {
        val result = capture.capture(taskId)
        val value = result.getOrElse {
            return ActionResult(false, "OCR 失败：${it.message}", retryable = true)
        }
        val bitmap = value.bitmap
        return try {
            val ocrResult = withContext(Dispatchers.Default) { ocr.recognize(bitmap) }
            val lines = JSONArray()
            ocrResult.lines.take(80).forEach { line ->
                lines.put(
                    JSONObject().put("text", line.text)
                        .put("bounds", JSONArray(listOf(line.left, line.top, line.right, line.bottom)))
                )
            }
            ActionResult(
                true, "识别到 ${ocrResult.lines.size} 行文字",
                JSONObject().put("text", ocrResult.fullText.take(6000))
                    .put("lines", lines)
                    .put("image", value.file?.absolutePath ?: "")
            )
        } catch (t: Throwable) {
            ActionResult(false, "OCR 识别失败：${t.message}", retryable = true)
        } finally {
            bitmap.recycle()
        }
    }

    // ----------------------------------------------------------------- acts

    private suspend fun tap(args: JSONObject): ActionResult {
        val service = AccessibilityBridge.service()
            ?: return ActionResult(false, "无障碍服务未连接，无法点击", retryable = true)
        val matcher = matcherFor(args)
        if (matcher != null) {
            val result = service.click(matcher)
            if (result.success) {
                return ActionResult(true, result.message, JSONObject().put("changed", result.changed), changed = result.changed)
            }
            val x = args.int("x", -1)
            val y = args.int("y", -1)
            if (x >= 0 && y >= 0) {
                val ok = service.tapPoint(x, y, 60)
                return ActionResult(ok, if (ok) "已按坐标点击 ($x,$y)" else "坐标点击失败", retryable = !ok)
            }
            return ActionResult(false, result.message, retryable = true)
        }
        val x = args.int("x", -1)
        val y = args.int("y", -1)
        if (x < 0 || y < 0) return ActionResult(false, "tap 需要 nodeId/text/resourceId 或 x/y 坐标")
        val ok = service.tapPoint(x, y, 60)
        return ActionResult(ok, if (ok) "已点击 ($x,$y)" else "点击失败", retryable = !ok)
    }

    private suspend fun longPress(args: JSONObject): ActionResult {
        val service = AccessibilityBridge.service()
            ?: return ActionResult(false, "无障碍服务未连接", retryable = true)
        val matcher = matcherFor(args)
        if (matcher != null) {
            val result = service.longClick(matcher)
            return ActionResult(result.success, result.message, retryable = !result.success)
        }
        val x = args.int("x", -1)
        val y = args.int("y", -1)
        if (x < 0 || y < 0) return ActionResult(false, "long_press 需要目标控件或坐标")
        val ok = service.tapPoint(x, y, 800)
        return ActionResult(ok, if (ok) "已长按 ($x,$y)" else "长按失败", retryable = !ok)
    }

    private suspend fun swipe(args: JSONObject): ActionResult {
        val service = AccessibilityBridge.service()
            ?: return ActionResult(false, "无障碍服务未连接", retryable = true)
        val x1 = args.int("x1", -1)
        val y1 = args.int("y1", -1)
        val x2 = args.int("x2", -1)
        val y2 = args.int("y2", -1)
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) return ActionResult(false, "swipe 需要 x1,y1,x2,y2")
        val duration = args.int("durationMs", 300).coerceIn(50, 3000).toLong()
        val ok = service.swipe(x1, y1, x2, y2, duration)
        return ActionResult(ok, if (ok) "已滑动 ($x1,$y1)→($x2,$y2)" else "滑动失败", retryable = !ok)
    }

    private suspend fun scroll(args: JSONObject): ActionResult {
        val service = AccessibilityBridge.service()
            ?: return ActionResult(false, "无障碍服务未连接", retryable = true)
        val direction = args.str("direction", "down")
        val matcher = if (args.has("nodeId")) matcherFor(args) else null
        val result = service.scroll(direction, matcher)
        return ActionResult(result.success, result.message, retryable = !result.success)
    }

    private suspend fun inputText(args: JSONObject): ActionResult {
        val service = AccessibilityBridge.service()
            ?: return ActionResult(false, "无障碍服务未连接", retryable = true)
        val text = args.str("text")
        if (text.isBlank()) return ActionResult(false, "text 不能为空")
        var matcher = matcherFor(args)
        if (matcher == null) {
            val editable = service.snapshot()?.findEditable()
                ?: return ActionResult(false, "当前界面没有找到输入框", retryable = true)
            matcher = NodeMatcher.fromNode(editable)
        }
        val result = service.inputText(matcher.copy(editableOnly = true), text)
        return ActionResult(result.success, result.message, retryable = !result.success)
    }

    private fun global(label: String, action: Int): ActionResult {
        val service = AccessibilityBridge.service()
            ?: return ActionResult(false, "无障碍服务未连接", retryable = true)
        val ok = when (action) {
            0 -> service.pressBack()
            1 -> service.pressHome()
            else -> service.openRecents()
        }
        return ActionResult(ok, if (ok) "已执行：$label" else "系统拒绝：$label", retryable = !ok)
    }

    // -------------------------------------------------------------- waiting

    private suspend fun waitForUi(args: JSONObject): ActionResult {
        val service = AccessibilityBridge.service()
            ?: return ActionResult(false, "无障碍服务未连接", retryable = true)
        val text = args.str("text")
        val pkg = args.str("package")
        if (text.isBlank() && pkg.isBlank()) return ActionResult(false, "wait_for_ui 需要 text 或 package")
        val timeout = args.int("timeoutMs", 5000).coerceIn(200, 30_000).toLong()
        val textOk = if (text.isNotBlank()) service.waitForText(text, timeout) else true
        val pkgOk = if (pkg.isNotBlank()) service.waitForPackage(pkg, if (text.isNotBlank()) 1000 else timeout) else true
        val ok = textOk && pkgOk
        return ActionResult(
            ok,
            when {
                ok -> "等待条件已满足"
                !textOk -> "超时：界面未出现文字「$text」"
                else -> "超时：前台应用不是 $pkg"
            },
            JSONObject().put("text_found", textOk).put("package_matched", pkgOk),
            retryable = !ok
        )
    }

    private suspend fun findElement(args: JSONObject): ActionResult {
        val tree = AccessibilityBridge.snapshot()
            ?: return ActionResult(false, "无障碍服务未连接", retryable = true)
        val text = args.str("text")
        val resourceId = args.str("resourceId")
        val desc = args.str("desc")
        val clickableOnly = args.bool("clickable")
        if (text.isBlank() && resourceId.isBlank() && desc.isBlank()) {
            return ActionResult(false, "find_element 需要 text/resourceId/desc 之一")
        }
        val matches = tree.nodes.filter { node ->
            (!clickableOnly || node.clickable) &&
                (text.isBlank() || node.matchesText(text, true)) &&
                (resourceId.isBlank() || node.resourceId == resourceId || node.resourceId.endsWith("/$resourceId")) &&
                (desc.isBlank() || node.desc.contains(desc, true))
        }.take(20)
        val array = JSONArray()
        matches.forEach { array.put(it.toJson()) }
        return ActionResult(
            matches.isNotEmpty(),
            if (matches.isEmpty()) "未找到匹配控件" else "找到 ${matches.size} 个匹配控件",
            JSONObject().put("matches", array),
            retryable = matches.isEmpty()
        )
    }

    private suspend fun verifyState(args: JSONObject): ActionResult {
        val tree = AccessibilityBridge.snapshot()
            ?: return ActionResult(false, "无障碍服务未连接", retryable = true)
        val text = args.str("text")
        val expectedPresent = args.bool("expect_present", true)
        val checkedText = args.str("checked_text")
        val pkg = args.str("package")
        val textFound = text.isBlank() || tree.nodes.any { it.matchesText(text, true) }
        val textOk = text.isBlank() || (textFound == expectedPresent)
        val checkedNode = if (checkedText.isBlank()) null else tree.nodes.firstOrNull { it.matchesText(checkedText, true) }
        val checkedOk = checkedText.isBlank() || (checkedNode != null && checkedNode.checked)
        val pkgOk = pkg.isBlank() || tree.packageName == pkg || AccessibilityBridge.foregroundPackage() == pkg
        val ok = textOk && checkedOk && pkgOk
        val detail = buildString {
            if (text.isNotBlank()) append("文字「$text」${if (textFound) "存在" else "不存在"}；")
            if (checkedText.isNotBlank()) append("「$checkedText」${if (checkedOk) "已勾选" else "未勾选"}；")
            if (pkg.isNotBlank()) append("前台 ${tree.packageName}；")
        }.trim()
        return ActionResult(
            ok, if (ok) "状态符合预期：$detail" else "状态不符合预期：$detail",
            JSONObject().put("text_found", textFound).put("checked", checkedOk)
                .put("package", tree.packageName).put("foreground", AccessibilityBridge.foregroundPackage())
        )
    }

    private suspend fun askConfirmation(args: JSONObject): ActionResult {
        val reason = args.str("reason").ifBlank { "模型请求确认下一步操作" }
        val allowed = confirmation.request(reason, Risk.MEDIUM)
        return ActionResult(
            allowed,
            if (allowed) "用户已允许：$reason" else "用户拒绝了：$reason",
            JSONObject().put("allowed", allowed),
            retryable = false
        )
    }

    // ------------------------------------------------------------- helpers

    private fun matcherFor(args: JSONObject): NodeMatcher? {
        val nodeId = args.int("nodeId", -1)
        if (nodeId >= 0) {
            val tree = AccessibilityBridge.snapshot()
            val node = tree?.byIndex(nodeId)
                ?: throw ActionValidationException("nodeId=$nodeId 已失效，请重新读取 UI")
            return NodeMatcher(index = node.index)
        }
        val text = args.str("text").takeIf { it.isNotBlank() }
        val resourceId = args.str("resourceId").takeIf { it.isNotBlank() }
        val desc = args.str("desc").takeIf { it.isNotBlank() }
        if (text == null && resourceId == null && desc == null) return null
        return NodeMatcher(text = text, resourceId = resourceId, desc = desc)
    }

    companion object {
        fun shortArgs(args: JSONObject, max: Int = 90): String {
            val text = args.toString()
            return if (text.length <= max) text else text.take(max) + "…"
        }
    }
}
