package com.kaori.lingtouch.chat

import android.content.Context
import com.kaori.lingtouch.api.ApiErrorKind
import com.kaori.lingtouch.api.ApiMessage
import com.kaori.lingtouch.api.ApiOutcome
import com.kaori.lingtouch.api.ChatOutput
import com.kaori.lingtouch.api.OpenCodeClient
import com.kaori.lingtouch.settings.SettingsRepository
import com.kaori.lingtouch.util.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Runs CHAT and WORK turns. CONTROL mode is still handled by the existing AgentEngine so
 * its verification/risk pipeline is preserved; this engine only owns pure chat and file work.
 */
class ChatEngine(
    private val context: Context,
    private val settings: SettingsRepository,
    private val api: OpenCodeClient,
    private val store: ChatStore
) {
    interface Listener {
        fun onStart()
        fun onDelta(text: String, reasoning: String)
        fun onTool(message: ChatMessage)
        fun onDone(message: ChatMessage)
        fun onError(message: String)
    }

    fun resolveModel(override: String): String =
        override.ifBlank { settings.state.value.model }

    suspend fun sendChat(
        session: ChatSession,
        modelOverride: String,
        userMessageId: Long,
        refsContext: String,
        listener: Listener
    ) {
        val model = resolveModel(modelOverride)
        if (model.isBlank()) {
            listener.onError("未选择模型")
            return
        }
        listener.onStart()
        val history = withContext(Dispatchers.IO) { store.messages(session.id) }
        val memory = withContext(Dispatchers.IO) { store.modeMemory(ChatMode.CHAT) }
        val messages = buildMessages(
            system = ChatPrompts.chat(memory),
            history = history,
            currentUserId = userMessageId,
            refsContext = refsContext
        )
        val outcome = api.chatStream(
            model = model,
            messages = trimForRequest(messages),
            jsonMode = false,
            maxTokens = 4096,
            onDelta = { text, reasoning -> listener.onDelta(text, reasoning) }
        )
        when (outcome) {
            is ApiOutcome.Err -> listener.onError(outcome.error.message)
            is ApiOutcome.Ok -> {
                val value = outcome.value
                val content = value.text.ifBlank {
                    if (value.reasoning.isNotBlank()) "（模型只返回了思考过程，没有最终回答）" else ""
                }
                if (content.isBlank()) {
                    listener.onError("模型返回为空")
                    return
                }
                val saved = withContext(Dispatchers.IO) {
                    store.addMessage(session.id, "assistant", content, value.reasoning)
                }
                listener.onDone(saved)
            }
        }
    }

    suspend fun sendWork(
        session: ChatSession,
        modelOverride: String,
        userMessageId: Long,
        refsContext: String,
        tools: WorkFileTools,
        listener: Listener
    ) {
        val model = resolveModel(modelOverride)
        if (model.isBlank()) {
            listener.onError("未选择模型")
            return
        }
        listener.onStart()
        val memory = withContext(Dispatchers.IO) { store.modeMemory(ChatMode.WORK) }
        val tree = runCatching {
            withContext(Dispatchers.IO) { tools.list("").take(120) }
        }.getOrElse { emptyList() }
        val treeText = tree.joinToString("\n") { file ->
            if (file.isDir) "[dir] ${file.path}" else "${file.path} (${file.size} B)"
        }
        val system = ChatPrompts.work(memory, session.workName, treeText)
        val history = withContext(Dispatchers.IO) { store.messages(session.id) }
        val messages = ArrayList(
            buildMessages(system, history, userMessageId, refsContext)
        )

        val reasoningAll = StringBuilder()
        var toolCalls = 0
        var formatErrors = 0
        var finalText: String? = null
        var finalReasoning = ""

        while (toolCalls < 30) {
            val outcome = api.chatStream(
                model = model,
                messages = trimForRequest(messages),
                jsonMode = true,
                maxTokens = 4096,
                onDelta = { _, reasoning ->
                    if (reasoning.isNotBlank()) {
                        listener.onDelta("", reasoningAll.toString() + reasoning)
                    }
                }
            )
            val value = when (outcome) {
                is ApiOutcome.Err -> {
                    if (outcome.error.kind == ApiErrorKind.BAD_REQUEST) {
                        // Some gateways reject response_format; retry once without it.
                        when (val retry = api.chatStream(
                            model, trimForRequest(messages), jsonMode = false, maxTokens = 4096,
                            onDelta = { _, reasoning ->
                                if (reasoning.isNotBlank()) {
                                    listener.onDelta("", reasoningAll.toString() + reasoning)
                                }
                            }
                        )) {
                            is ApiOutcome.Ok -> retry.value
                            is ApiOutcome.Err -> {
                                listener.onError(retry.error.message)
                                return
                            }
                        }
                    } else {
                        listener.onError(outcome.error.message)
                        return
                    }
                }
                is ApiOutcome.Ok -> outcome.value
            }
            finalReasoning = value.reasoning
            if (value.reasoning.isNotBlank()) {
                reasoningAll.append(value.reasoning).append("\n\n")
            }
            val text = value.text.trim()
            val action = parseAction(text)
            if (action == null) {
                // A plain prose answer ends the turn; JSON-looking-but-unparseable output is retried.
                if (text.isNotBlank() && !looksLikeJson(text)) {
                    finalText = text
                    break
                }
                formatErrors++
                if (formatErrors >= 2) {
                    finalText = text.ifBlank { "模型没有返回可解析的工具调用。" }
                    break
                }
                messages.add(
                    ApiMessage(
                        "user",
                        "上一条输出不是合法的单个 JSON 对象。请严格输出 {\"thought\":\"...\",\"action\":\"工具名\",\"args\":{...}}，不要使用 Markdown。"
                    )
                )
                continue
            }
            val name = action.optString("action").trim()
            val args = action.optJSONObject("args") ?: JSONObject()
            if (name == "finish" || name == "final" || name == "answer") {
                finalText = args.optString("summary").ifBlank {
                    action.optString("summary").ifBlank { text }
                }
                break
            }
            if (name.isBlank()) {
                finalText = action.optString("summary").ifBlank { text }
                break
            }
            toolCalls++
            messages.add(ApiMessage("assistant", text.take(12_000)))
            val result = runCatching { executeTool(tools, name, args) }
                .getOrElse { "工具执行失败：${it.message ?: it.javaClass.simpleName}" }
            val toolContent = buildString {
                append("工具 ").append(name).append(' ').append(args.toString().take(1200))
                append("\n结果：").append(result.take(16_000))
            }
            val savedTool = withContext(Dispatchers.IO) {
                store.addMessage(session.id, "tool", toolContent)
            }
            listener.onTool(savedTool)
            messages.add(ApiMessage("user", "工具结果（$name）：\n${result.take(12_000)}"))
            if (toolCalls >= 30) {
                finalText = "已达到单轮工具调用上限（30 次），停止执行。已完成的操作见上方工具记录。"
                break
            }
        }

        val content = finalText?.trim().orEmpty()
        if (content.isBlank()) {
            listener.onError("模型没有给出最终回答")
            return
        }
        val reasoning = reasoningAll.toString().ifBlank { finalReasoning }
        val saved = withContext(Dispatchers.IO) {
            store.addMessage(session.id, "assistant", content, reasoning)
        }
        listener.onDone(saved)
    }

    // -------------------------------------------------------------- helpers

    private fun buildMessages(
        system: String,
        history: List<ChatMessage>,
        currentUserId: Long,
        refsContext: String
    ): List<ApiMessage> {
        val messages = ArrayList<ApiMessage>()
        messages.add(ApiMessage("system", system))
        history.takeLast(60).forEach { message ->
            if (message.id == 0L && message.content.isBlank()) return@forEach
            val content = when {
                message.isUser && message.id == currentUserId ->
                    listOf(message.content, refsContext)
                        .filter { it.isNotBlank() }
                        .joinToString("\n\n")
                message.isUser -> message.content
                message.isAssistant -> message.content
                message.isTool -> "工具结果：\n" + message.content
                else -> ""
            }
            if (content.isBlank()) return@forEach
            messages.add(
                ApiMessage(
                    role = if (message.isAssistant) "assistant" else "user",
                    text = content.take(24_000)
                )
            )
        }
        return messages
    }

    private fun parseAction(text: String): JSONObject? {
        if (text.isBlank()) return null
        val cleaned = text.trim()
            .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(cleaned.substring(start, end + 1)) }.getOrNull()
    }

    private fun looksLikeJson(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.startsWith("{") && trimmed.endsWith("}")
    }

    private suspend fun executeTool(tools: WorkFileTools, name: String, args: JSONObject): String {
        val path = args.optString("path").ifBlank { "" }
        return when (name) {
            "list_files" -> {
                val files = tools.list(path)
                files.joinToString("\n") {
                    if (it.isDir) "[dir] ${it.path}" else "${it.path} (${it.size} B, ${it.mime})"
                }.ifBlank { "（空目录）" }
            }
            "read_file" -> tools.read(
                path = path,
                encoding = args.optString("encoding").ifBlank { "text" },
                maxBytes = args.optInt("max_bytes", 240_000).coerceIn(1_000, 1_000_000)
            )
            "write_file" -> tools.write(
                path = path,
                content = args.optString("content"),
                encoding = args.optString("encoding").ifBlank { "text" }
            )
            "edit_file" -> tools.edit(
                path = path,
                find = args.optString("find"),
                replace = args.optString("replace"),
                all = args.optBoolean("all", false)
            )
            "delete_file" -> tools.delete(path)
            "search_files" -> tools.search(
                query = args.optString("query"),
                path = args.optString("path"),
                max = args.optInt("max", 80).coerceIn(1, 200)
            )
            "file_info" -> tools.info(path)
            else -> "未知工具 \"$name\"。可用工具：list_files, read_file, write_file, edit_file, delete_file, search_files, file_info"
        }
    }
    private fun trimForRequest(messages: List<ApiMessage>): List<ApiMessage> {
        if (messages.isEmpty()) return messages
        val system = messages.firstOrNull { it.role == "system" }
        val rest = if (system != null) messages.drop(1) else messages
        var total = system?.text?.length ?: 0
        val kept = ArrayDeque<ApiMessage>()
        for (message in rest.asReversed()) {
            val cost = message.text.length + 32
            if (kept.isNotEmpty() && total + cost > 90_000) break
            kept.addFirst(message)
            total += cost
        }
        return buildList {
            system?.let { add(it) }
            addAll(kept)
        }
    }

}
