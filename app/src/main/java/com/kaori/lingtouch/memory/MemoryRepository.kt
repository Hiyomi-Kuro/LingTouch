package com.kaori.lingtouch.memory

import com.kaori.lingtouch.util.Json
import com.kaori.lingtouch.util.bool
import com.kaori.lingtouch.util.int
import com.kaori.lingtouch.util.Json.parseObject
import com.kaori.lingtouch.util.str
import com.kaori.lingtouch.util.Logx
import org.json.JSONArray
import org.json.JSONObject

data class MemoryContext(
    val memories: List<MemoryEntity>,
    val experiences: List<ExperienceEntity>
) {
    val isEmpty: Boolean get() = memories.isEmpty() && experiences.isEmpty()

    fun toPromptText(): String {
        if (isEmpty) return ""
        val sb = StringBuilder()
        if (memories.isNotEmpty()) {
            sb.appendLine("【历史记忆】")
            memories.forEach { m ->
                val tag = when (m.type) {
                    MemoryType.APP -> "应用"
                    MemoryType.TASK -> "任务"
                    MemoryType.GENERAL -> "通用"
                }
                val scope = m.appLabel?.takeIf { it.isNotBlank() } ?: m.packageName ?: ""
                val verified = if (m.verified) "已验证" else "未验证"
                sb.appendLine("- [$tag${if (scope.isNotBlank()) "·$scope" else ""}·$verified] ${m.title}: ${m.content.take(300)}")
            }
        }
        if (experiences.isNotEmpty()) {
            sb.appendLine("【可复用经验】")
            experiences.forEach { e ->
                sb.appendLine("- 触发「${e.trigger.take(60)}」→ 方法：${e.method.take(240)}（成功${e.successCount}次）")
            }
        }
        return sb.toString().trim()
    }
}

/**
 * Turns completed task traces into durable, editable knowledge, and retrieves the
 * most relevant lessons for a new goal. User-entered text is never persisted here.
 */
class MemoryRepository(private val db: LingTouchDb) {

    fun learnFromTask(
        taskId: Long,
        goal: String,
        steps: List<TaskStepEntity>,
        success: Boolean,
        summary: String?
    ): Int {
        if (steps.isEmpty()) return 0
        var inserted = 0
        val safeSteps = steps.map { sanitizeAction(it) }
        val chain = safeSteps.joinToString(" → ") { it.first }
        val packageName = steps.asSequence().mapNotNull { extractPackage(it.argsJson) }.firstOrNull()
        val label = packageName?.let { pkg -> db.allApps().firstOrNull { it.packageName == pkg }?.label }
        val resultWord = if (success) "成功" else "失败"

        val taskTitle = "任务：${goal.take(70)}"
        val taskContent = buildString {
            appendLine("结果：$resultWord")
            if (safeSteps.isNotEmpty()) appendLine("执行路径：$chain")
            if (!summary.isNullOrBlank()) appendLine("总结：${summary.take(300)}")
        }.trim()
        upsert(
            MemoryEntity(
                type = MemoryType.TASK,
                packageName = packageName,
                appLabel = label,
                title = taskTitle,
                content = taskContent,
                verified = success,
                successCount = if (success) 1 else 0,
                failCount = if (success) 0 else 1
            )
        )
        inserted++

        if (success && packageName != null) {
            upsert(
                MemoryEntity(
                    type = MemoryType.APP,
                    packageName = packageName,
                    appLabel = label,
                    title = "${label ?: packageName} 操作经验：${goal.take(50)}",
                    content = "目标：${goal.take(120)}\n成功路径：$chain",
                    verified = true,
                    successCount = 1
                )
            )
            inserted++
        }

        if (success && steps.size >= 2) {
            recordExperience(packageName, goal, safeSteps, chain)
            inserted++
        }
        Logx.d("Memory", "任务 $taskId 学习完成，写入 $inserted 条记忆")
        return inserted
    }

    fun contextFor(goal: String, packageName: String?): MemoryContext {
        val keys = keywords(goal)
        val memories = db.relevantMemories(keys, packageName, 6)
        val experiences = db.relevantExperiences(packageName, keys, 3)
        return MemoryContext(memories, experiences)
    }

    fun recordUsage(context: MemoryContext, success: Boolean) {
        context.memories.forEach { db.markMemoryUsed(it.id, success) }
        context.experiences.forEach { db.markExperienceUsed(it.id, success) }
    }

    fun save(memory: MemoryEntity): Long {
        return if (memory.id > 0) {
            db.updateMemory(memory)
            memory.id
        } else {
            db.insertMemory(memory)
        }
    }

    fun delete(id: Long) = db.deleteMemory(id)

    fun clearAll() = db.clearMemories()

    fun list(type: MemoryType?, query: String?, limit: Int = 500): List<MemoryEntity> =
        db.memories(type, query, limit)

    fun forPackage(pkg: String): List<MemoryEntity> = db.memoriesForPackage(pkg)

    fun count(): Int = db.memoryCount()

    fun experiences(): List<ExperienceEntity> = db.allExperiences()

    // ------------------------------------------------------------ internals

    private fun upsert(memory: MemoryEntity): Long {
        val existing = db.memories(memory.type, memory.title.take(40))
            .firstOrNull { it.title == memory.title && it.packageName == memory.packageName }
        return if (existing != null) {
            db.updateMemory(
                existing.copy(
                    content = memory.content,
                    verified = existing.verified || memory.verified,
                    successCount = existing.successCount + memory.successCount,
                    failCount = existing.failCount + memory.failCount,
                    expired = false
                )
            )
            existing.id
        } else {
            db.insertMemory(memory)
        }
    }

    private fun recordExperience(packageName: String?, goal: String, actions: List<Pair<String, JSONObject>>, chain: String) {
        val existing = db.allExperiences().firstOrNull {
            it.packageName == packageName && it.trigger == goal.take(120)
        }
        val stepsJson = JSONArray().apply {
            actions.forEach { (action, args) -> put(JSONObject().put("action", action).put("args", args)) }
        }.toString()
        if (existing != null) {
            db.writableDatabase.execSQL(
                "UPDATE experiences SET method=?, steps_json=?, verified=1, confidence=MIN(0.95, confidence+0.1), success_count=success_count+1, updated_at=? WHERE id=?",
                arrayOf(chain, stepsJson, System.currentTimeMillis(), existing.id)
            )
        } else {
            db.insertExperience(
                ExperienceEntity(
                    packageName = packageName,
                    taskType = category(goal),
                    trigger = goal.take(120),
                    method = chain,
                    stepsJson = stepsJson,
                    verified = true,
                    confidence = 0.6f,
                    successCount = 1
                )
            )
        }
    }

    /** Keeps only action names and structural targets; drops user text and URLs. */
    private fun sanitizeAction(step: TaskStepEntity): Pair<String, JSONObject> {
        val args = Json.parseObject(step.argsJson) ?: JSONObject()
        val safe = JSONObject()
        listOf("package", "activity", "resourceId", "class", "direction", "nodeId").forEach { key ->
            if (args.has(key)) safe.put(key, args.optString(key).take(80))
        }
        if (args.has("text") && step.action !in setOf("input_text")) {
            safe.put("text", args.optString("text").take(30))
        }
        if (args.has("query")) safe.put("query", args.optString("query").take(30))
        val label = when {
            step.action == "tap" && args.has("text") -> "tap(\"${args.optString("text").take(16)}\")"
            args.has("resourceId") -> "${step.action}(${args.optString("resourceId").takeLast(30)})"
            args.has("package") -> "${step.action}(${args.optString("package")})"
            else -> step.action
        }
        return label to safe
    }

    private fun extractPackage(argsJson: String?): String? {
        val args = Json.parseObject(argsJson) ?: return null
        return args.optString("package").takeIf { it.isNotBlank() }
    }

    private fun category(goal: String): String = when {
        goal.contains("设置") || goal.contains("set", true) -> "settings"
        goal.contains("打开") || goal.contains("open", true) -> "launch"
        goal.contains("输入") || goal.contains("type", true) -> "input"
        else -> "general"
    }

    companion object {
        /** Chinese bigrams + latin tokens; enough for LIKE-based recall without a tokenizer. */
        fun keywords(goal: String): List<String> {
            val result = LinkedHashSet<String>()
            Regex("[A-Za-z0-9_]{3,}").findAll(goal).forEach { result.add(it.value.lowercase()) }
            Regex("[\\u4e00-\\u9fa5]{2,}").findAll(goal).forEach { match ->
                val text = match.value
                if (text.length <= 4) {
                    result.add(text)
                } else {
                    for (i in 0 until text.length - 1) result.add(text.substring(i, i + 2))
                }
            }
            return result.filter { it.length >= 2 }.take(10)
        }
    }

    // --------------------------------------------------------- import/export

    fun exportJson(): JSONObject {
        val memories = JSONArray()
        db.memories(null, null, 5000).forEach { m ->
            memories.put(
                JSONObject()
                    .put("type", m.type.name)
                    .put("package", m.packageName)
                    .put("appLabel", m.appLabel)
                    .put("title", m.title)
                    .put("content", m.content)
                    .put("verified", m.verified)
                    .put("useCount", m.useCount)
                    .put("successCount", m.successCount)
                    .put("failCount", m.failCount)
                    .put("updatedAt", m.updatedAt)
            )
        }
        val experiences = JSONArray()
        db.allExperiences().forEach { e ->
            experiences.put(
                JSONObject()
                    .put("package", e.packageName)
                    .put("trigger", e.trigger)
                    .put("method", e.method)
                    .put("steps", e.stepsJson)
                    .put("verified", e.verified)
                    .put("confidence", e.confidence.toDouble())
                    .put("successCount", e.successCount)
            )
        }
        return JSONObject()
            .put("app", "LingTouch")
            .put("version", 1)
            .put("exportedAt", System.currentTimeMillis())
            .put("memories", memories)
            .put("experiences", experiences)
    }

    fun importJson(text: String): Int {
        val root = parseObject(text) ?: throw IllegalArgumentException("不是合法 JSON")
        var count = 0
        val memories = root.optJSONArray("memories") ?: JSONArray()
        for (i in 0 until memories.length()) {
            val item = memories.optJSONObject(i) ?: continue
            val title = item.str("title")
            if (title.isBlank()) continue
            val pkg = item.optString("package").takeIf { it.isNotBlank() }
            val exists = db.memories(MemoryType.from(item.str("type")), title.take(40))
                .any { it.title == title && it.packageName == pkg }
            if (exists) continue
            db.insertMemory(
                MemoryEntity(
                    type = MemoryType.from(item.str("type")),
                    packageName = pkg,
                    appLabel = item.optString("appLabel").takeIf { it.isNotBlank() },
                    title = title.take(200),
                    content = item.str("content").take(4000),
                    verified = item.bool("verified"),
                    useCount = item.int("useCount"),
                    successCount = item.int("successCount"),
                    failCount = item.int("failCount")
                )
            )
            count++
        }
        val experiences = root.optJSONArray("experiences") ?: JSONArray()
        for (i in 0 until experiences.length()) {
            val item = experiences.optJSONObject(i) ?: continue
            val trigger = item.str("trigger")
            val method = item.str("method")
            if (trigger.isBlank() || method.isBlank()) continue
            db.insertExperience(
                ExperienceEntity(
                    packageName = item.optString("package").takeIf { it.isNotBlank() },
                    trigger = trigger.take(200),
                    method = method.take(2000),
                    stepsJson = item.optString("steps"),
                    verified = item.bool("verified"),
                    confidence = item.optDouble("confidence", 0.5).toFloat(),
                    successCount = item.int("successCount")
                )
            )
            count++
        }
        Logx.d("Memory", "导入 $count 条记忆")
        return count
    }
}
