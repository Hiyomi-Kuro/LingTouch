package com.kaori.lingtouch.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.kaori.lingtouch.util.Logx
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The device-operation engine. Exposes a stable, validated API to the agent tools;
 * the agent never receives raw node references and never executes arbitrary commands.
 */
class LingTouchAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityBridge.attach(this)
        Logx.i("A11y", "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                event.packageName?.toString()?.takeIf { it.isNotBlank() }?.let { ForegroundState.packageName = it }
                event.className?.toString()?.takeIf { it.isNotBlank() }?.let { cls ->
                    if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                        ForegroundState.activityName = cls
                        ForegroundState.windowTitle = event.text?.joinToString(" ").orEmpty()
                    }
                }
                ForegroundState.lastEventAt = System.currentTimeMillis()
                ForegroundState.lastEventType = event.eventType
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
        AccessibilityBridge.detach(this)
        Logx.i("A11y", "无障碍服务已断开")
    }

    // ------------------------------------------------------------- snapshot

    fun snapshot(): UiTree? {
        val (w, h) = screenSize()
        return UiTreeReader.read(
            service = this,
            activityName = ForegroundState.activityName,
            windowTitle = ForegroundState.windowTitle,
            width = w,
            height = h,
            rotation = rotation()
        )
    }

    fun screenSize(): Pair<Int, Int> {
        runCatching {
            val wm = getSystemService(WindowManager::class.java)
            val bounds = wm.currentWindowMetrics.bounds
            if (bounds.width() > 0 && bounds.height() > 0) return bounds.width() to bounds.height()
        }
        val metrics = resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    fun rotation(): Int {
        runCatching {
            val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            return display?.rotation ?: Surface.ROTATION_0
        }
        return Surface.ROTATION_0
    }

    fun foregroundPackage(): String =
        rootInActiveWindow?.packageName?.toString()?.takeIf { it.isNotBlank() }
            ?: ForegroundState.packageName

    // --------------------------------------------------------------- lookup

    fun findNode(matcher: NodeMatcher): AccessibilityNodeInfo? {
        if (matcher.isEmpty) return null
        if (matcher.index != null) return findByIndex(matcher.index)
        val roots = currentRoots()
        roots.forEach { root ->
            val found = search(root, matcher, 0)
            if (found != null) return found
        }
        return null
    }

    private fun currentRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        runCatching {
            windows?.forEach { window ->
                val root = window.root
                if (root != null && roots.none { it == root }) roots.add(root)
            }
        }
        return roots
    }

    private fun search(node: AccessibilityNodeInfo, matcher: NodeMatcher, depth: Int): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH) return null
        if (matches(node, matcher)) return node
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            val found = search(child, matcher, depth + 1)
            if (found != null) return found
        }
        return null
    }

    private fun findByIndex(target: Int): AccessibilityNodeInfo? {
        var counter = 0
        var result: AccessibilityNodeInfo? = null

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (result != null || depth > MAX_DEPTH) return
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val visible = node.isVisibleToUser && rect.width() > 0 && rect.height() > 0
            if (visible && isInteresting(node)) {
                if (counter == target) {
                    result = node
                    return
                }
                counter++
            }
            for (i in 0 until node.childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                visit(child, depth + 1)
                if (result != null) return
            }
        }

        currentRoots().forEach { root ->
            visit(root, 0)
            if (result != null) return result
        }
        return result
    }

    private fun isInteresting(node: AccessibilityNodeInfo): Boolean =
        !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank() ||
            node.isClickable || node.isEditable || node.isScrollable || node.isCheckable ||
            !node.viewIdResourceName.isNullOrBlank()

    private fun matches(node: AccessibilityNodeInfo, matcher: NodeMatcher): Boolean {
        if (!node.isVisibleToUser) return false
        if (matcher.editableOnly && !node.isEditable) return false
        if (matcher.clickableOnly && !node.isClickable) return false
        if (matcher.scrollableOnly && !node.isScrollable) return false
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val id = node.viewIdResourceName.orEmpty()

        matcher.text?.takeIf { it.isNotBlank() }?.let { q ->
            val hit = if (matcher.contains) {
                text.contains(q, ignoreCase = true) || desc.contains(q, ignoreCase = true)
            } else {
                text.equals(q, ignoreCase = true) || desc.equals(q, ignoreCase = true)
            }
            if (!hit) return false
        }
        matcher.desc?.takeIf { it.isNotBlank() }?.let { q ->
            if (!desc.contains(q, ignoreCase = true) && !text.contains(q, ignoreCase = true)) return false
        }
        matcher.resourceId?.takeIf { it.isNotBlank() }?.let { q ->
            if (id != q && !id.endsWith("/$q")) return false
        }
        return true
    }

    fun matches(matcher: NodeMatcher, limit: Int = 10): List<UiNode> {
        val tree = snapshot() ?: return emptyList()
        return tree.nodes.filter { node ->
            (matcher.text.isNullOrBlank() || node.matchesText(matcher.text, matcher.contains)) &&
                (matcher.resourceId.isNullOrBlank() || node.resourceId == matcher.resourceId || node.resourceId.endsWith("/${matcher.resourceId}")) &&
                (!matcher.editableOnly || node.editable) &&
                (!matcher.clickableOnly || node.clickable) &&
                (!matcher.scrollableOnly || node.scrollable)
        }.take(limit)
    }

    // -------------------------------------------------------------- actions

    suspend fun click(matcher: NodeMatcher): OpResult {
        val before = snapshot()?.signature().orEmpty()
        val node = findNode(matcher) ?: return OpResult(false, "未找到目标控件")
        val label = describe(node)
        val clickable = closestClickable(node)
        if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            delay(SETTLE_MS)
            return OpResult(true, "已点击 $label", changed = signatureChanged(before))
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return OpResult(false, "$label 没有可点击区域")
        val tapped = tapPoint(rect.centerX(), rect.centerY(), 60)
        delay(SETTLE_MS)
        return OpResult(tapped, if (tapped) "已点击坐标 $label" else "点击失败：$label", signatureChanged(before))
    }

    suspend fun longClick(matcher: NodeMatcher): OpResult {
        val node = findNode(matcher) ?: return OpResult(false, "未找到目标控件")
        if (node.isLongClickable && node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
            delay(SETTLE_MS)
            return OpResult(true, "已长按 ${describe(node)}")
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val ok = tapPoint(rect.centerX(), rect.centerY(), 800)
        delay(SETTLE_MS)
        return OpResult(ok, if (ok) "已长按 ${describe(node)}" else "长按失败")
    }

    suspend fun tapPoint(x: Int, y: Int, durationMs: Long = 60): Boolean {
        val (w, h) = screenSize()
        if (x !in 0 until w || y !in 0 until h) return false
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatch(path, durationMs.coerceIn(1, 2000))
    }

    suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long = 300): Boolean {
        val (w, h) = screenSize()
        if (startX !in 0 until w || startY !in 0 until h || endX !in 0 until w || endY !in 0 until h) return false
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        return dispatch(path, durationMs.coerceIn(50, 3000))
    }

    private suspend fun dispatch(path: Path, durationMs: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            val accepted = runCatching { dispatchGesture(gesture, callback, null) }.getOrDefault(false)
            if (!accepted && cont.isActive) cont.resume(false)
        }

    fun inputText(matcher: NodeMatcher, text: String): OpResult {
        val node = findNode(matcher) ?: return OpResult(false, "未找到输入框")
        if (!node.isEditable) return OpResult(false, "目标不是输入框")
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            val readback = node.text?.toString().orEmpty()
            val confirmed = readback.trim() == text.trim() || (text.isNotBlank() && readback.contains(text))
            return OpResult(
                true,
                if (confirmed) "已输入 ${text.length} 个字符（已回读校验）" else "已提交输入 ${text.length} 个字符，但未能回读内容",
                changed = confirmed,
                readback = readback
            )
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("lingtouch", text))
        val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        val readback = if (pasted) node.text?.toString().orEmpty() else ""
        val confirmed = pasted && (readback.trim() == text.trim() || (text.isNotBlank() && readback.contains(text)))
        return OpResult(
            pasted,
            if (pasted) "已通过粘贴输入 ${text.length} 个字符" else "输入失败",
            changed = confirmed,
            readback = readback
        )
    }


    fun scroll(direction: String, matcher: NodeMatcher? = null): OpResult {
        val target = matcher?.let { findNode(it) } ?: findScrollableNode()
        if (target == null) return OpResult(false, "当前界面没有可滚动区域")
        val before = snapshot()?.signature().orEmpty()
        val action = when (ScrollSemantics.resolve(direction)) {
            ScrollAxis.BACKWARD -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            ScrollAxis.FORWARD -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            ScrollAxis.LEFT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
            ScrollAxis.RIGHT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
        }
        val ok = target.performAction(action)
        val changed = ok && signatureChanged(before)
        val message = if (ok) {
            "已滚动 $direction（${ScrollSemantics.describe(direction)}）" +
                if (changed) "，界面已变化" else "，界面未变化"
        } else {
            "滚动失败：${ScrollSemantics.describe(direction)}"
        }
        return OpResult(ok, message, changed)
    }


    private fun findScrollableNode(): AccessibilityNodeInfo? {
        var result: AccessibilityNodeInfo? = null
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (result != null || depth > MAX_DEPTH) return
            if (node.isScrollable && node.isVisibleToUser) {
                result = node
                return
            }
            for (i in 0 until node.childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                visit(child, depth + 1)
                if (result != null) return
            }
        }
        currentRoots().forEach { visit(it, 0) }
        return result
    }

    fun global(action: Int): Boolean = performGlobalAction(action)

    fun pressBack(): Boolean = global(GLOBAL_ACTION_BACK)

    fun pressHome(): Boolean = global(GLOBAL_ACTION_HOME)

    fun openRecents(): Boolean = global(GLOBAL_ACTION_RECENTS)

    fun notifications(): Boolean = global(GLOBAL_ACTION_NOTIFICATIONS)

    // -------------------------------------------------------------- waiting

    suspend fun waitForText(text: String, timeoutMs: Long, contains: Boolean = true): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(200, 30_000)
        while (System.currentTimeMillis() < deadline) {
            if (snapshot()?.findByText(text, contains) != null) return true
            delay(250)
        }
        return false
    }

    suspend fun waitForPackage(pkg: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(200, 30_000)
        while (System.currentTimeMillis() < deadline) {
            if (foregroundPackage() == pkg) return true
            delay(250)
        }
        return false
    }

    suspend fun waitForChange(previousSignature: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(200, 20_000)
        while (System.currentTimeMillis() < deadline) {
            val current = snapshot()?.signature().orEmpty()
            if (current != previousSignature) return true
            delay(250)
        }
        return false
    }

    suspend fun screenshot(): Bitmap? = suspendCancellableCoroutine { cont ->
        val callback = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                val bitmap = runCatching {
                    val hardware = screenshot.hardwareBuffer
                    val wrapped = Bitmap.wrapHardwareBuffer(hardware, screenshot.colorSpace)
                    val copy = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                    wrapped?.recycle()
                    hardware.close()
                    copy
                }.getOrNull()
                if (cont.isActive) cont.resume(bitmap)
            }

            override fun onFailure(errorCode: Int) {
                Logx.w("A11y", "无障碍截图失败 code=$errorCode")
                if (cont.isActive) cont.resume(null)
            }
        }
        val accepted = runCatching {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
            true
        }.getOrDefault(false)
        if (!accepted && cont.isActive) cont.resume(null)
    }

    private fun signatureChanged(previous: String): Boolean {
        val current = snapshot()?.signature().orEmpty()
        return current.isNotEmpty() && current != previous
    }

    private fun closestClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) return node
        var parent = node.parent
        var guard = 0
        while (parent != null && guard++ < 8) {
            if (parent.isClickable) return parent
            parent = parent.parent
        }
        return null
    }

    private fun describe(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?: node.viewIdResourceName?.substringAfterLast('/')
            ?: node.className?.toString()?.substringAfterLast('.')
        return "「${text ?: "控件"}」"
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        AccessibilityBridge.detach(this)
        return super.onUnbind(intent)
    }

    private companion object {
        const val MAX_DEPTH = 32
        const val SETTLE_MS = 350L
    }
}
