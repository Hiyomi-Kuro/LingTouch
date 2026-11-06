package com.kaori.lingtouch.memory

enum class MemoryType {
    APP, GENERAL, TASK;

    companion object {
        fun from(value: String?): MemoryType = entries.firstOrNull { it.name == value } ?: GENERAL
    }
}

data class AppEntity(
    val id: Long = 0,
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val versionName: String?,
    val lastSeen: Long = System.currentTimeMillis(),
    val archived: Boolean = false
)

data class TaskEntity(
    val id: Long = 0,
    val goal: String,
    val status: String,
    val plan: String? = null,
    val summary: String? = null,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val stepCount: Int = 0,
    val success: Boolean = false
)

data class TaskStepEntity(
    val id: Long = 0,
    val taskId: Long,
    val stepIndex: Int,
    val action: String,
    val argsJson: String?,
    val resultJson: String?,
    val ok: Boolean,
    val durationMs: Long,
    val createdAt: Long = System.currentTimeMillis()
)

data class MemoryEntity(
    val id: Long = 0,
    val type: MemoryType,
    val packageName: String? = null,
    val appLabel: String? = null,
    val title: String,
    val content: String,
    val verified: Boolean = false,
    val expired: Boolean = false,
    val useCount: Int = 0,
    val successCount: Int = 0,
    val failCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = 0
)

data class ExperienceEntity(
    val id: Long = 0,
    val packageName: String? = null,
    val taskType: String? = null,
    val trigger: String,
    val method: String,
    val stepsJson: String? = null,
    val verified: Boolean = false,
    val confidence: Float = 0.5f,
    val successCount: Int = 0,
    val failCount: Int = 0,
    val errorType: String? = null,
    val recovery: String? = null,
    val uiSignature: String? = null,
    val appVersion: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
