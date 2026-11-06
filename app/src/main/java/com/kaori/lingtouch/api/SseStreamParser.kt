package com.kaori.lingtouch.api

import com.kaori.lingtouch.util.Json
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure parser for one SSE `data:` payload. Kept free of Android/OkHttp dependencies so the
 * streaming protocol can be unit-tested with fixtures from every supported provider.
 */
data class SseDelta(
    val text: String = "",
    val reasoning: String = "",
    val finish: String = "",
    val error: String? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0
) {
    val isEmpty: Boolean
        get() = text.isEmpty() && reasoning.isEmpty() && finish.isEmpty() && error == null &&
            promptTokens == 0 && completionTokens == 0
}

object SseStreamParser {

    fun parseData(payload: String): SseDelta? {
        if (payload.isBlank() || payload == "[DONE]") return null
        val root = Json.parseObject(payload) ?: return null

        root.optJSONObject("error")?.let { error ->
            val message = error.optString("message").ifBlank { error.toString() }
            return SseDelta(error = message)
        }
        if (root.optString("type") == "error") {
            val message = root.optString("message").ifBlank { root.optString("error") }
            return SseDelta(error = message.ifBlank { "流式响应返回错误" })
        }

        var text = ""
        var reasoning = ""
        var finish = ""
        var prompt = 0
        var completion = 0

        // Anthropic content_block_delta
        root.optJSONObject("delta")?.let { delta ->
            when (delta.optString("type")) {
                "text_delta" -> text += delta.optString("text")
                "thinking_delta" -> reasoning += delta.optString("thinking")
                "input_json_delta" -> Unit
                else -> Unit
            }
        }

        // OpenAI Responses streaming events
        when (root.optString("type")) {
            "response.output_text.delta" -> text += root.optString("delta")
            "response.reasoning_text.delta", "response.reasoning_summary_text.delta",
            "response.reasoning.delta" -> reasoning += root.optString("delta")
            "response.completed" -> {
                finish = "stop"
                root.optJSONObject("response")?.optJSONObject("usage")?.let {
                    prompt = it.optInt("input_tokens", it.optInt("prompt_tokens", 0))
                    completion = it.optInt("output_tokens", it.optInt("completion_tokens", 0))
                }
            }
        }

        val choice = root.optJSONArray("choices")?.optJSONObject(0)
        if (choice != null) {
            val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message")
            val content = delta?.opt("content") ?: choice.opt("text")
            text += contentText(content)
            reasoning += delta?.optString("reasoning_content").orEmpty()
            reasoning += delta?.optString("reasoning").orEmpty()
            reasoning += delta?.optString("analysis").orEmpty()
            reasoning += reasoningParts(content)
            finish = choice.optString("finish_reason").ifBlank { finish }
        }

        val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
        if (candidate != null) {
            val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
            if (parts != null) {
                for (index in 0 until parts.length()) {
                    val part = parts.optJSONObject(index) ?: continue
                    val value = part.optString("text")
                    if (value.isBlank()) continue
                    if (part.optBoolean("thought") || part.optString("type") == "thinking") {
                        reasoning += value
                    } else {
                        text += value
                    }
                }
            }
            finish = candidate.optString("finishReason").ifBlank { finish }
        }

        root.optJSONObject("usage")?.let { usage ->
            prompt = usage.optInt("prompt_tokens", usage.optInt("input_tokens", prompt))
            completion = usage.optInt("completion_tokens", usage.optInt("output_tokens", completion))
        }
        root.optJSONObject("message")?.optJSONObject("usage")?.let { usage ->
            prompt = usage.optInt("input_tokens", usage.optInt("prompt_tokens", prompt))
            completion = usage.optInt("output_tokens", usage.optInt("completion_tokens", completion))
        }

        val result = SseDelta(text, reasoning, finish, null, prompt, completion)
        return if (result.isEmpty) null else result
    }

    fun contentText(content: Any?): String = when (content) {
        null, JSONObject.NULL -> ""
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
