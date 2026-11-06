package com.kaori.lingtouch.api

import com.kaori.lingtouch.util.Json
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tolerant parser for chat-completion responses. Handles plain JSON, SSE streams, the
 * OpenAI Responses shape, Anthropic/Gemini fallbacks used by compatibility gateways, and
 * reasoning-only answers that would otherwise look like an empty HTTP 200 response.
 */
data class ParsedResponse(
    val text: String,
    val reasoning: String,
    val finishReason: String,
    val source: String
) {
    val hasFinalText: Boolean get() = text.isNotBlank()
}

object ChatResponseParser {

    fun parse(protocol: ApiProtocol, rawBody: String): ParsedResponse? {
        if (rawBody.isBlank()) return null
        parseSse(rawBody)?.let { sse ->
            if (sse.text.isNotBlank() || sse.reasoning.isNotBlank()) return sse
        }
        val root = Json.parseObject(rawBody) ?: return null
        return parseRoot(protocol, root, 0)
    }

    /** Extracts an API error message even when the gateway wrapped it in HTTP 200. */
    fun errorMessage(rawBody: String): String? {
        val candidates = ArrayList<String>()
        if (rawBody.isNotBlank()) candidates.add(rawBody)
        ssePayloads(rawBody).forEach { candidates.add(it) }
        for (candidate in candidates) {
            val root = Json.parseObject(candidate) ?: continue
            val error = root.optJSONObject("error")
                ?: root.optJSONObject("detail")
                ?: if (root.optString("type") == "error") root else null
            if (error != null) {
                val message = error.optString("message")
                    .ifBlank { error.optString("detail") }
                    .ifBlank { error.toString() }
                if (message.isNotBlank()) return message.take(400)
            }
        }
        return null
    }

    /** Short, privacy-safe structural summary used in diagnostics. */
    fun structure(rawBody: String): String {
        if (rawBody.isBlank()) return "空响应体"
        val candidate = if (Json.parseObject(rawBody) != null) rawBody else firstSsePayload(rawBody)
        val root = candidate?.let { Json.parseObject(it) } ?: return "非 JSON 响应（${rawBody.length} 字符）"
        val keys = root.keys().asSequence().take(8).joinToString(",")
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
        val message = choice?.optJSONObject("message") ?: choice?.optJSONObject("delta")
        val messageKeys = message?.keys()?.asSequence()?.take(8)?.joinToString(",").orEmpty()
        val finish = choice?.optString("finish_reason").orEmpty()
        return buildString {
            append("keys=[").append(keys).append("]")
            if (messageKeys.isNotBlank()) append(" message=[").append(messageKeys).append("]")
            append(" finish_reason=").append(finish.ifBlank { "(none)" })
        }
    }

    // ------------------------------------------------------------- internals

    private fun parseRoot(protocol: ApiProtocol, root: JSONObject, depth: Int): ParsedResponse? {
        if (depth > 3) return null
        root.optJSONObject("data")?.let { inner ->
            if (inner.has("choices") || inner.has("content") || inner.has("candidates") || inner.has("output")) {
                parseRoot(protocol, inner, depth + 1)?.let { return it }
            }
        }
        root.optJSONObject("response")?.let { inner ->
            parseRoot(protocol, inner, depth + 1)?.let { return it }
        }

        root.optJSONObject("result")?.let { inner ->
            parseRoot(protocol, inner, depth + 1)?.let { return it }
        }

        val openAi = parseOpenAi(root)
        val anthropic = parseAnthropic(root)
        val gemini = parseGemini(root)
        val ordered = when (protocol) {
            ApiProtocol.OPENAI_CHAT, ApiProtocol.OPENAI_RESPONSES ->
                listOf(openAi, anthropic, gemini)
            ApiProtocol.ANTHROPIC -> listOf(anthropic, openAi, gemini)
            ApiProtocol.GEMINI -> listOf(gemini, openAi, anthropic)
        }.filterNotNull()
        ordered.firstOrNull { it.hasFinalText }?.let { return it }
        val direct = root.optString("text").ifBlank { root.optString("content") }
        if (direct.isNotBlank()) return ParsedResponse(direct, "", "", "text")
        return ordered.firstOrNull { it.reasoning.isNotBlank() }
    }

    private fun parseOpenAi(root: JSONObject): ParsedResponse? {
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
        if (choice != null) {
            val message = choice.optJSONObject("message") ?: choice.optJSONObject("delta")
            val content = message?.opt("content") ?: choice.opt("text")
            val text = contentText(content)
            val reasoning = buildString {
                append(message?.optString("reasoning_content").orEmpty())
                append(message?.optString("reasoning").orEmpty())
                append(message?.optString("analysis").orEmpty())
                append(reasoningParts(content))
            }
            if (text.isNotBlank() || reasoning.isNotBlank()) {
                return ParsedResponse(text, reasoning, choice.optString("finish_reason"), "openai")
            }
        }

        val outputText = root.optString("output_text")
        if (outputText.isNotBlank()) return ParsedResponse(outputText, "", "", "responses")

        val output = root.optJSONArray("output")
        if (output != null) {
            val text = StringBuilder()
            val reasoning = StringBuilder()
            for (index in 0 until output.length()) {
                val item = output.optJSONObject(index) ?: continue
                val content = item.optJSONArray("content") ?: continue
                for (partIndex in 0 until content.length()) {
                    val part = content.optJSONObject(partIndex) ?: continue
                    when (part.optString("type")) {
                        "output_text", "text" -> text.append(part.optString("text"))
                        "reasoning", "reasoning_text", "summary_text", "thinking" ->
                            reasoning.append(part.optString("text"))
                    }
                }
            }
            if (text.isNotBlank() || reasoning.isNotBlank()) {
                return ParsedResponse(text.toString(), reasoning.toString(), "", "responses")
            }
        }
        return null
    }

    private fun parseAnthropic(root: JSONObject): ParsedResponse? {
        val content = root.optJSONArray("content") ?: return null
        val text = StringBuilder()
        val reasoning = StringBuilder()
        for (index in 0 until content.length()) {
            val part = content.optJSONObject(index) ?: continue
            when (part.optString("type")) {
                "text", "output_text" -> text.append(part.optString("text"))
                "thinking", "reasoning", "redacted_thinking" ->
                    reasoning.append(part.optString("thinking").ifBlank { part.optString("text") })
            }
        }
        if (text.isBlank() && reasoning.isBlank()) return null
        return ParsedResponse(text.toString(), reasoning.toString(), root.optString("stop_reason"), "anthropic")
    }

    private fun parseGemini(root: JSONObject): ParsedResponse? {
        val candidate = root.optJSONArray("candidates")?.optJSONObject(0) ?: return null
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: return null
        val text = StringBuilder()
        val reasoning = StringBuilder()
        for (index in 0 until parts.length()) {
            val part = parts.optJSONObject(index) ?: continue
            val value = part.optString("text")
            if (value.isBlank()) continue
            if (part.optBoolean("thought") || part.optString("type") == "thinking") {
                reasoning.append(value)
            } else {
                text.append(value)
            }
        }
        if (text.isBlank() && reasoning.isBlank()) return null
        return ParsedResponse(text.toString(), reasoning.toString(), candidate.optString("finishReason"), "gemini")
    }

    private fun parseSse(raw: String): ParsedResponse? {
        if (!raw.contains("data:")) return null
        val text = StringBuilder()
        val reasoning = StringBuilder()
        var finish = ""
        var seen = false
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) return@forEach
            val payload = trimmed.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") return@forEach
            val root = Json.parseObject(payload) ?: return@forEach
            val choice = root.optJSONArray("choices")?.optJSONObject(0) ?: return@forEach
            seen = true
            finish = choice.optString("finish_reason").ifBlank { finish }
            val delta = choice.optJSONObject("delta")
            val message = choice.optJSONObject("message")
            text.append(contentText(delta?.opt("content") ?: message?.opt("content") ?: choice.opt("text")))
            reasoning.append(delta?.optString("reasoning_content").orEmpty())
            reasoning.append(delta?.optString("reasoning").orEmpty())
            reasoning.append(message?.optString("reasoning_content").orEmpty())
        }
        if (!seen) return null
        return ParsedResponse(text.toString(), reasoning.toString(), finish, "sse")
    }

    private fun ssePayloads(raw: String): List<String> {
        if (!raw.contains("data:")) return emptyList()
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .filter { it.isNotEmpty() && it != "[DONE]" }
            .toList()
    }

    private fun firstSsePayload(raw: String): String? = ssePayloads(raw).lastOrNull()

    private fun contentText(content: Any?): String = when (content) {
        null -> ""
        is String -> content
        is JSONArray -> buildString {
            for (index in 0 until content.length()) {
                val item = content.opt(index)
                when (item) {
                    is String -> append(item)
                    is JSONObject -> {
                        val type = item.optString("type")
                        if (type != "reasoning" && type != "thinking" && type != "reasoning_text") {
                            append(item.optString("text").ifBlank { item.optString("content") })
                        }
                    }
                }
            }
        }
        is JSONObject -> content.optString("text").ifBlank { content.optString("content") }
        else -> content.toString()
    }

    private fun reasoningParts(content: Any?): String {
        if (content !is JSONArray) return ""
        return buildString {
            for (index in 0 until content.length()) {
                val item = content.optJSONObject(index) ?: continue
                if (item.optString("type") in setOf("reasoning", "thinking", "reasoning_text")) {
                    append(item.optString("text"))
                }
            }
        }
    }
}
