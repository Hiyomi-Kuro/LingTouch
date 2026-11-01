package com.kaori.lingtouch.tools

import org.json.JSONObject

enum class Risk(val level: Int) {
    LOW(0), MEDIUM(1), HIGH(2);

    companion object {
        fun from(value: String?): Risk = when (value?.lowercase()) {
            "high", "高" -> HIGH
            "medium", "中" -> MEDIUM
            else -> LOW
        }
    }
}

enum class ArgType { STRING, INT, BOOL, ENUM }

data class ToolArg(
    val name: String,
    val type: ArgType,
    val required: Boolean,
    val description: String,
    val values: List<String> = emptyList(),
    val min: Int = Int.MIN_VALUE,
    val max: Int = Int.MAX_VALUE
)

data class ToolSpec(
    val name: String,
    val description: String,
    val args: List<ToolArg>,
    val risk: Risk,
    val group: String,
    val requiresOneOf: List<String> = emptyList()
)

data class AgentAction(
    val name: String,
    val args: JSONObject,
    val thought: String = "",
    val expect: String = "",
    val declaredRisk: Risk = Risk.LOW
) {
    fun signature(): String = "$name:${args}"
}

class ActionValidationException(message: String) : Exception(message)
