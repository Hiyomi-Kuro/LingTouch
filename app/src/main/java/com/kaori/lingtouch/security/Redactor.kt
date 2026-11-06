package com.kaori.lingtouch.security

import org.json.JSONArray
import org.json.JSONObject

/**
 * Central redaction for anything persisted or exported. Input text typed by the user is
 * never stored verbatim, and password/token/verification-code patterns are masked.
 */
object Redactor {
    private val sensitiveKeyParts = listOf(
        "password", "passwd", "passcode", "pwd", "secret", "token", "apikey", "api_key",
        "authorization", "cookie", "credential", "验证码", "密码", "口令", "密钥",
        "身份证", "银行卡", "card_number"
    )

    private val sensitiveHints = sensitiveKeyParts + listOf(
        "验证码", "校验码", "动态码", "短信", "otp", "pin", "verification"
    )

    private val keyValuePattern = Regex(
        "(?i)(password|passwd|pwd|secret|token|api[_-]?key|authorization|验证码|密码|口令|密钥)" +
            "\\s*[:=：]\\s*[^\\s,;，；]{1,80}"
    )
    private val bearerPattern = Regex("(?i)bearer\\s+[A-Za-z0-9._\\-]{8,}")
    private val skPattern = Regex("\\bsk-[A-Za-z0-9_\\-]{6,}")
    private val codePattern = Regex(
        "(验证码|校验码|动态码|verification\\s*code|otp)\\D{0,8}\\d{4,8}",
        RegexOption.IGNORE_CASE
    )

    fun isSensitiveKey(key: String): Boolean {
        val lower = key.lowercase()
        return sensitiveKeyParts.any { lower.contains(it) }
    }

    fun isSensitiveHint(vararg hints: String): Boolean = hints.any { hint ->
        hint.isNotBlank() && sensitiveHints.any { hint.contains(it, ignoreCase = true) }
    }

    /**
     * Returns a safe copy of tool arguments for persistence. When [sensitiveTarget] is true
     * every typed value is hidden, because it may be a password even without a keyword.
     */
    fun redactArgs(action: String, args: JSONObject, sensitiveTarget: Boolean = false): JSONObject {
        val out = JSONObject()
        val keys = args.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = args.opt(key)
            when {
                isSensitiveKey(key) -> out.put(key, "***")
                value is String && (action == "input_text" || sensitiveTarget) && key == "text" ->
                    out.put(key, "[已隐藏输入 ${value.length} 字]")
                value is String -> out.put(key, redactText(value))
                else -> out.put(key, value)
            }
        }
        return out
    }

    fun redactText(text: String): String {
        if (text.isBlank()) return text
        var out = bearerPattern.replace(text, "Bearer ***")
        out = skPattern.replace(out, "sk-***")
        out = keyValuePattern.replace(out) { match ->
            val prefix = match.groupValues[1]
            "$prefix=***"
        }
        out = codePattern.replace(out) { match ->
            val prefix = match.groupValues[1]
            "$prefix ***"
        }
        return out
    }

    fun redactJson(raw: String?): String {
        if (raw.isNullOrBlank()) return raw.orEmpty()
        val obj = runCatching { JSONObject(raw) }.getOrNull()
        if (obj == null) return redactText(raw)
        return redactObject(obj).toString()
    }

    private fun redactObject(obj: JSONObject): JSONObject {
        val out = JSONObject()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.opt(key)
            if (isSensitiveKey(key)) {
                out.put(key, "***")
            } else {
                out.put(key, redactValue(value))
            }
        }
        return out
    }

    private fun redactValue(value: Any?): Any? = when (value) {
        is JSONObject -> redactObject(value)
        is JSONArray -> JSONArray().apply {
            for (index in 0 until value.length()) put(redactValue(value.opt(index)))
        }
        is String -> redactText(value)
        else -> value
    }
}
