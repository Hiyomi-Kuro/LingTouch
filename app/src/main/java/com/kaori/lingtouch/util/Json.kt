package com.kaori.lingtouch.util

import org.json.JSONArray
import org.json.JSONObject

/** Tolerant JSON helpers used across API parsing and tool arguments. */
object Json {
    fun parseObject(raw: String?): JSONObject? {
        if (raw.isNullOrBlank()) return null
        val text = stripFence(raw)
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            extractBalanced(text)?.let {
                try { JSONObject(it) } catch (_: Exception) { null }
            }
        }
    }

    fun parseArray(raw: String?): JSONArray? {
        if (raw.isNullOrBlank()) return null
        val text = stripFence(raw)
        return try {
            JSONArray(text)
        } catch (_: Exception) {
            val s = text.indexOf('[')
            val e = text.lastIndexOf(']')
            if (s >= 0 && e > s) try { JSONArray(text.substring(s, e + 1)) } catch (_: Exception) { null } else null
        }
    }

    private fun stripFence(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```")) {
            val nl = text.indexOf('\n')
            if (nl > 0) text = text.substring(nl + 1)
            if (text.endsWith("```")) text = text.dropLast(3)
        }
        return text.trim()
    }

    private fun extractBalanced(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }
}

fun JSONObject.str(key: String, def: String = ""): String =
    if (isNull(key)) def else optString(key, def)

fun JSONObject.int(key: String, def: Int = 0): Int = optInt(key, def)

fun JSONObject.bool(key: String, def: Boolean = false): Boolean = optBoolean(key, def)

fun JSONObject.obj(key: String): JSONObject? = optJSONObject(key)

fun JSONObject.arr(key: String): JSONArray? = optJSONArray(key)

fun JSONObject.putIfNotNull(key: String, value: Any?): JSONObject {
    if (value != null) put(key, value)
    return this
}
