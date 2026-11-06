package com.kaori.lingtouch.tools

import com.kaori.lingtouch.util.Json
import org.json.JSONArray
import org.json.JSONObject

object ActionParser {
    /**
     * Accepts exactly one JSON action object. Tolerates markdown fences, wrapper objects
     * and stringified arguments, but never treats free text as a command.
     */
    fun parse(text: String): AgentAction {
        val root = Json.parseObject(text)
            ?: throw ActionValidationException("输出不是合法 JSON 对象")
        val holder = unwrap(root)
        val name = firstNonBlank(
            holder.optString("action"),
            holder.optString("tool"),
            holder.optString("name"),
            holder.optString("action_name")
        )
        if (name.isBlank()) throw ActionValidationException("缺少 action 字段")
        val args = holder.optJSONObject("args")
            ?: holder.optJSONObject("arguments")
            ?: holder.optJSONObject("parameters")
            ?: JSONObject()
        return AgentAction(
            name = name.trim(),
            args = args,
            thought = holder.optString("thought").take(600),
            expect = firstNonBlank(holder.optString("expect"), holder.optString("expected")),
            declaredRisk = Risk.from(holder.optString("risk")),
            plan = planArray(holder)
        )
    }

    private fun planArray(holder: JSONObject): JSONArray? =
        holder.optJSONArray("plan")
            ?: holder.optJSONArray("subgoals")
            ?: holder.optJSONArray("task_plan")
            ?: holder.optJSONObject("plan")?.let {
                it.optJSONArray("subgoals") ?: it.optJSONArray("steps")
            }

    private fun unwrap(root: JSONObject): JSONObject {
        listOf("step", "action_object", "output", "result").forEach { key ->
            root.optJSONObject(key)?.let { if (it.has("action") || it.has("tool")) return it }
        }
        return root
    }

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
}
