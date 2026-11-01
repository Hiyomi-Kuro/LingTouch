package com.kaori.lingtouch.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.kaori.lingtouch.util.Logx

/** Traverses the live accessibility tree into an immutable [UiTree]. */
object UiTreeReader {
    private const val MAX_NODES = 500
    private const val MAX_DEPTH = 32

    fun read(
        service: AccessibilityService,
        activityName: String,
        windowTitle: String,
        width: Int,
        height: Int,
        rotation: Int
    ): UiTree? {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        val active = service.rootInActiveWindow
        if (active != null) roots.add(active)
        runCatching {
            service.windows?.forEach { window ->
                val root = window.root
                if (root != null && roots.none { it == root }) roots.add(root)
            }
        }
        if (roots.isEmpty()) return null
        val rootPackage = active?.packageName?.toString().orEmpty()

        val nodes = ArrayList<UiNode>(128)
        var counter = 0
        var windowCount = 0
        roots.forEach { root ->
            windowCount++
            counter = traverse(root, 0, nodes, counter, 0)
        }
        return UiTree(
            packageName = rootPackage,
            activityName = activityName,
            windowTitle = windowTitle,
            width = width,
            height = height,
            rotation = rotation,
            nodes = nodes,
            timestamp = System.currentTimeMillis(),
            windowCount = windowCount
        )
    }

    private fun traverse(
        node: AccessibilityNodeInfo,
        depth: Int,
        out: MutableList<UiNode>,
        startIndex: Int,
        windowOffset: Int
    ): Int {
        var counter = startIndex
        if (depth > MAX_DEPTH || counter >= MAX_NODES) return counter
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val visible = node.isVisibleToUser && rect.width() > 0 && rect.height() > 0
        if (!visible) {
            // Still walk children: a container can be invisible while a child is visible.
            for (i in 0 until node.childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                counter = traverse(child, depth + 1, out, counter, windowOffset)
                runCatching { child.recycle() }
                if (counter >= MAX_NODES) break
            }
            return counter
        }

        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val resourceId = node.viewIdResourceName.orEmpty()
        val clickable = node.isClickable
        val editable = node.isEditable
        val scrollable = node.isScrollable
        val checkable = node.isCheckable
        val interesting = text.isNotBlank() || desc.isNotBlank() || clickable || editable ||
            scrollable || checkable || resourceId.isNotBlank()

        if (interesting) {
            out.add(
                UiNode(
                    index = counter,
                    className = node.className?.toString().orEmpty(),
                    text = text,
                    desc = desc,
                    resourceId = resourceId,
                    packageName = node.packageName?.toString().orEmpty(),
                    left = rect.left,
                    top = rect.top,
                    right = rect.right,
                    bottom = rect.bottom,
                    clickable = clickable,
                    longClickable = node.isLongClickable,
                    editable = editable,
                    scrollable = scrollable,
                    checkable = checkable,
                    checked = node.isChecked,
                    selected = node.isSelected,
                    enabled = node.isEnabled,
                    focused = node.isFocused,
                    depth = depth
                )
            )
            counter++
        }

        for (i in 0 until node.childCount) {
            if (counter >= MAX_NODES) break
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            counter = traverse(child, depth + 1, out, counter, windowOffset)
            runCatching { child.recycle() }
        }
        return counter
    }
}
