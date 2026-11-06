package com.kaori.lingtouch.agent

import com.kaori.lingtouch.accessibility.UiTree
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight, immutable description of what is on screen right now. A new instance is
 * created for every observation; snapshots and node indexes are never reused across steps.
 */
data class ScreenState(
    val packageName: String,
    val activityName: String,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val signature: String,
    val keyTexts: List<String>,
    val nodeCount: Int,
    val hasEditable: Boolean,
    val hasScrollable: Boolean,
    val keyboardVisible: Boolean,
    val dialogVisible: Boolean,
    val timestamp: Long,
    val accessibility: Boolean,
    val screenshot: Boolean,
    val ocr: Boolean
) {
    val label: String
        get() {
            val pkg = packageName.ifBlank { "未知应用" }
            val activity = activityName.substringAfterLast('.')
            return if (activity.isBlank()) pkg else "$pkg/$activity"
        }

    fun changedFrom(previous: ScreenState?): Boolean =
        signature.isNotBlank() && previous != null && signature != previous.signature

    fun toJson(): JSONObject = JSONObject()
        .put("package", packageName)
        .put("activity", activityName)
        .put("width", width)
        .put("height", height)
        .put("rotation", rotation)
        .put("signature", signature)
        .put("node_count", nodeCount)
        .put("editable", hasEditable)
        .put("scrollable", hasScrollable)
        .put("keyboard", keyboardVisible)
        .put("dialog", dialogVisible)
        .put("key_texts", JSONArray(keyTexts))

    companion object {
        fun from(
            tree: UiTree?,
            accessibility: Boolean,
            screenshot: Boolean = false,
            ocr: Boolean = false
        ): ScreenState {
            if (tree == null) {
                return ScreenState(
                    packageName = "",
                    activityName = "",
                    width = 0,
                    height = 0,
                    rotation = 0,
                    signature = "",
                    keyTexts = emptyList(),
                    nodeCount = 0,
                    hasEditable = false,
                    hasScrollable = false,
                    keyboardVisible = false,
                    dialogVisible = false,
                    timestamp = System.currentTimeMillis(),
                    accessibility = accessibility,
                    screenshot = screenshot,
                    ocr = ocr
                )
            }
            val signature = buildString {
                append(tree.packageName).append('|')
                append(tree.activityName).append('|')
                append(tree.nodes.size).append('|')
                append(Integer.toHexString(tree.signature().hashCode()))
            }
            val keyTexts = tree.textNodes()
                .map { it.label.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(12)
                .map { if (it.length <= 40) it else it.take(40) + "…" }
            return ScreenState(
                packageName = tree.packageName,
                activityName = tree.activityName,
                width = tree.width,
                height = tree.height,
                rotation = tree.rotation,
                signature = signature,
                keyTexts = keyTexts,
                nodeCount = tree.nodes.size,
                hasEditable = tree.nodes.any { it.editable },
                hasScrollable = tree.nodes.any { it.scrollable },
                keyboardVisible = tree.nodes.any { it.packageName.contains("inputmethod", true) },
                dialogVisible = tree.nodes.any {
                    it.shortClass.contains("Dialog", true) || it.resourceId.contains("dialog", true)
                },
                timestamp = tree.timestamp,
                accessibility = accessibility,
                screenshot = screenshot,
                ocr = ocr
            )
        }

        fun empty(accessibility: Boolean = false): ScreenState = from(null, accessibility)
    }
}
