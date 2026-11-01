package com.kaori.lingtouch.accessibility

import com.kaori.lingtouch.util.Format
import org.json.JSONArray
import org.json.JSONObject

/** Immutable snapshot of one accessible node. Node references are never kept. */
data class UiNode(
    val index: Int,
    val className: String,
    val text: String,
    val desc: String,
    val resourceId: String,
    val packageName: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val selected: Boolean,
    val enabled: Boolean,
    val focused: Boolean,
    val depth: Int
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val label: String get() = text.ifBlank { desc }.ifBlank { resourceId.substringAfterLast('/') }
    val shortClass: String get() = className.substringAfterLast('.')

    fun flags(): String = buildString {
        if (clickable) append("click ")
        if (longClickable) append("long ")
        if (editable) append("edit ")
        if (scrollable) append("scroll ")
        if (checkable) append(if (checked) "checked " else "unchecked ")
        if (selected) append("selected ")
        if (focused) append("focus ")
        if (!enabled) append("disabled ")
    }.trim()

    fun promptLine(): String {
        val b = "$left,$top,$right,$bottom"
        val t = if (text.isBlank()) "" else " text=\"${Format.truncate(text.replace("\n", " "), 60)}\""
        val d = if (desc.isBlank()) "" else " desc=\"${Format.truncate(desc.replace("\n", " "), 40)}\""
        val id = if (resourceId.isBlank()) "" else " id=${resourceId.substringAfterLast('/')}"
        return "#$index $shortClass$t$d$id b=[$b] ${flags()}".trim()
    }

    fun toJson(): JSONObject = JSONObject()
        .put("index", index)
        .put("class", className)
        .put("text", text)
        .put("desc", desc)
        .put("id", resourceId)
        .put("package", packageName)
        .put("bounds", JSONArray(listOf(left, top, right, bottom)))
        .put("clickable", clickable)
        .put("longClickable", longClickable)
        .put("editable", editable)
        .put("scrollable", scrollable)
        .put("checked", checked)
        .put("checkable", checkable)
        .put("enabled", enabled)
        .put("focused", focused)

    fun matchesText(query: String, contains: Boolean = false): Boolean {
        if (query.isBlank()) return false
        val q = query.trim()
        val candidates = listOf(text, desc, resourceId.substringAfterLast('/'))
        return candidates.any {
            it.equals(q, ignoreCase = true) || (contains && it.contains(q, ignoreCase = true))
        }
    }
}

data class UiTree(
    val packageName: String,
    val activityName: String,
    val windowTitle: String,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val nodes: List<UiNode>,
    val timestamp: Long,
    val windowCount: Int
) {
    fun signature(): String = nodes.joinToString("|") { "${it.index}:${it.text}:${it.resourceId}:${it.checked}" }

    fun textNodes(): List<UiNode> = nodes.filter { it.text.isNotBlank() || it.desc.isNotBlank() }

    fun screenText(): String {
        val seen = LinkedHashSet<String>()
        nodes.forEach { node ->
            val value = node.text.ifBlank { node.desc }.trim()
            if (value.length >= 1) seen.add(value)
        }
        return seen.joinToString("\n")
    }

    fun byIndex(index: Int): UiNode? = nodes.firstOrNull { it.index == index }

    fun findByText(query: String, contains: Boolean = false): UiNode? =
        nodes.firstOrNull { it.matchesText(query, contains) }

    fun findByResourceId(resourceId: String): UiNode? = nodes.firstOrNull {
        it.resourceId == resourceId || it.resourceId.endsWith("/$resourceId")
    }

    fun findEditable(): UiNode? = nodes.firstOrNull { it.editable && it.enabled } ?: nodes.firstOrNull { it.editable }

    fun prompt(limit: Int = 120): String {
        val selected = nodes
            .filter { it.text.isNotBlank() || it.desc.isNotBlank() || it.clickable || it.editable || it.scrollable || it.checkable }
            .take(limit)
        if (selected.isEmpty()) return "(空界面，没有可访问节点)"
        return selected.joinToString("\n") { it.promptLine() }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("package", packageName)
        .put("activity", activityName)
        .put("width", width)
        .put("height", height)
        .put("rotation", rotation)
        .put("nodes", JSONArray().apply { nodes.forEach { put(it.toJson()) } })

    companion object {
        fun empty(width: Int, height: Int): UiTree = UiTree(
            packageName = "", activityName = "", windowTitle = "", width = width, height = height,
            rotation = 0, nodes = emptyList(), timestamp = System.currentTimeMillis(), windowCount = 0
        )
    }
}
