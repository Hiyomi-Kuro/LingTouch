package com.kaori.lingtouch.chat

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds a self-contained Markdown export including the complete reasoning of every turn. */
object ChatExporter {

    fun fileName(mode: ChatMode, now: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        return "lingtouch-${mode.name.lowercase()}-$stamp.md"
    }

    fun build(
        mode: ChatMode,
        session: ChatSession?,
        model: String,
        messages: List<ChatMessage>,
        memory: String,
        now: Long = System.currentTimeMillis()
    ): String = buildString {
        append("# 灵触对话 · ").append(modeLabel(mode)).append("\n\n")
        append("- 导出时间：").append(
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now))
        ).append('\n')
        append("- 模型：").append(model.ifBlank { "default" }).append('\n')
        session?.title?.takeIf { it.isNotBlank() }?.let {
            append("- 标题：").append(it).append('\n')
        }
        if (session?.workName?.isNotBlank() == true) {
            append("- 工作目录：").append(session.workName).append('\n')
        }
        if (memory.isNotBlank()) {
            append("\n## 模式记忆\n\n").append(memory.trim()).append('\n')
        }
        append("\n---\n")
        messages.forEach { message ->
            if (message.isTool) {
                append("\n### 工具\n\n").append(message.content.trim()).append('\n')
                return@forEach
            }
            append("\n## ").append(if (message.isUser) "用户" else "助手").append('\n')
            if (message.reasoning.isNotBlank()) {
                append("\n### 思考过程\n\n").append(message.reasoning.trim()).append('\n')
            }
            append("\n### 回答\n\n").append(message.content.trim()).append('\n')
            stepsOf(message.meta).takeIf { it.isNotEmpty() }?.let { steps ->
                append("\n### 执行步骤\n\n")
                steps.forEach { append("- ").append(it).append('\n') }
            }
        }
    }

    fun stepsOf(meta: String?): List<String> {
        if (meta.isNullOrBlank()) return emptyList()
        return runCatching {
            val root = JSONObject(meta)
            val array = root.optJSONArray("steps") ?: return emptyList()
            val list = ArrayList<String>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val ok = item.optBoolean("ok", true)
                val mark = if (ok) "✓" else "✗"
                val action = item.optString("action")
                val detail = item.optString("detail")
                val message = item.optString("message")
                list.add(
                    listOf(mark, action, detail, message)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                )
            }
            list
        }.getOrDefault(emptyList())
    }

    fun modeLabel(mode: ChatMode): String = when (mode) {
        ChatMode.CONTROL -> "操控"
        ChatMode.CHAT -> "对话"
        ChatMode.WORK -> "工作"
    }
}
