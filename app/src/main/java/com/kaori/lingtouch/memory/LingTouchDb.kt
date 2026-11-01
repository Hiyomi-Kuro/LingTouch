package com.kaori.lingtouch.memory

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.kaori.lingtouch.util.Logx

/**
 * Local structured store. Uses a plain SQLiteOpenHelper with explicit schema and
 * migration hooks so the app has no annotation-processing dependency and every
 * schema change is reviewable.
 */
class LingTouchDb(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE apps(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                package_name TEXT NOT NULL UNIQUE,
                label TEXT NOT NULL,
                is_system INTEGER NOT NULL DEFAULT 0,
                version_name TEXT,
                last_seen INTEGER NOT NULL,
                archived INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE tasks(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                goal TEXT NOT NULL,
                status TEXT NOT NULL,
                plan TEXT,
                summary TEXT,
                started_at INTEGER NOT NULL,
                finished_at INTEGER,
                step_count INTEGER NOT NULL DEFAULT 0,
                success INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE task_steps(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                task_id INTEGER NOT NULL,
                step_index INTEGER NOT NULL,
                action TEXT NOT NULL,
                args_json TEXT,
                result_json TEXT,
                ok INTEGER NOT NULL DEFAULT 0,
                duration_ms INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(task_id) REFERENCES tasks(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE memories(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type TEXT NOT NULL,
                package_name TEXT,
                app_label TEXT,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                verified INTEGER NOT NULL DEFAULT 0,
                expired INTEGER NOT NULL DEFAULT 0,
                use_count INTEGER NOT NULL DEFAULT 0,
                success_count INTEGER NOT NULL DEFAULT 0,
                fail_count INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                last_used_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE experiences(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                package_name TEXT,
                task_type TEXT,
                trigger_text TEXT NOT NULL,
                method TEXT NOT NULL,
                steps_json TEXT,
                verified INTEGER NOT NULL DEFAULT 0,
                confidence REAL NOT NULL DEFAULT 0.5,
                success_count INTEGER NOT NULL DEFAULT 0,
                fail_count INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_steps_task ON task_steps(task_id, step_index)")
        db.execSQL("CREATE INDEX idx_mem_type ON memories(type, package_name)")
        db.execSQL("CREATE INDEX idx_mem_updated ON memories(updated_at)")
        db.execSQL("CREATE INDEX idx_exp_pkg ON experiences(package_name)")
        db.execSQL("CREATE INDEX idx_tasks_started ON tasks(started_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Migration policy: every future version adds an explicit, additive migration here.
        // Never drop user memories as part of an upgrade.
        var version = oldVersion
        if (version < 2) {
            version = 2
        }
    }

    // ------------------------------------------------------------------ apps

    fun upsertApps(apps: List<AppEntity>) {
        if (apps.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            apps.forEach { app ->
                val values = ContentValues().apply {
                    put("package_name", app.packageName)
                    put("label", app.label)
                    put("is_system", if (app.isSystem) 1 else 0)
                    put("version_name", app.versionName)
                    put("last_seen", now)
                    put("archived", if (app.archived) 1 else 0)
                }
                db.insertWithOnConflict("apps", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun allApps(): List<AppEntity> = readableDatabase.query(
        "apps", null, "archived=0", null, null, null, "is_system ASC, label COLLATE NOCASE ASC"
    ).use { c -> buildList { while (c.moveToNext()) add(c.toApp()) } }

    // ----------------------------------------------------------------- tasks

    fun createTask(goal: String): Long {
        val values = ContentValues().apply {
            put("goal", goal)
            put("status", "RUNNING")
            put("started_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("tasks", null, values)
    }

    fun setTaskPlan(taskId: Long, plan: String) {
        writableDatabase.update("tasks", ContentValues().apply { put("plan", plan) }, "id=?", arrayOf(taskId.toString()))
    }

    fun updateTaskStatus(taskId: Long, status: String) {
        writableDatabase.update("tasks", ContentValues().apply { put("status", status) }, "id=?", arrayOf(taskId.toString()))
    }

    fun finishTask(taskId: Long, status: String, summary: String?, success: Boolean, stepCount: Int) {
        val values = ContentValues().apply {
            put("status", status)
            put("summary", summary)
            put("finished_at", System.currentTimeMillis())
            put("success", if (success) 1 else 0)
            put("step_count", stepCount)
        }
        writableDatabase.update("tasks", values, "id=?", arrayOf(taskId.toString()))
    }

    fun task(taskId: Long): TaskEntity? = readableDatabase.query(
        "tasks", null, "id=?", arrayOf(taskId.toString()), null, null, null
    ).use { c -> if (c.moveToFirst()) c.toTask() else null }

    fun recentTasks(limit: Int = 30): List<TaskEntity> = readableDatabase.query(
        "tasks", null, null, null, null, null, "started_at DESC", limit.toString()
    ).use { c -> buildList { while (c.moveToNext()) add(c.toTask()) } }

    fun deleteAllTasks() {
        writableDatabase.delete("tasks", null, null)
    }

    // ----------------------------------------------------------------- steps

    fun addStep(step: TaskStepEntity): Long {
        val values = ContentValues().apply {
            put("task_id", step.taskId)
            put("step_index", step.stepIndex)
            put("action", step.action)
            put("args_json", step.argsJson)
            put("result_json", step.resultJson)
            put("ok", if (step.ok) 1 else 0)
            put("duration_ms", step.durationMs)
            put("created_at", step.createdAt)
        }
        return writableDatabase.insert("task_steps", null, values)
    }

    fun steps(taskId: Long): List<TaskStepEntity> = readableDatabase.query(
        "task_steps", null, "task_id=?", arrayOf(taskId.toString()), null, null, "step_index ASC"
    ).use { c -> buildList { while (c.moveToNext()) add(c.toStep()) } }

    // -------------------------------------------------------------- memories

    fun insertMemory(memory: MemoryEntity): Long {
        val values = ContentValues().apply {
            put("type", memory.type.name)
            put("package_name", memory.packageName)
            put("app_label", memory.appLabel)
            put("title", memory.title)
            put("content", memory.content)
            put("verified", if (memory.verified) 1 else 0)
            put("expired", if (memory.expired) 1 else 0)
            put("use_count", memory.useCount)
            put("success_count", memory.successCount)
            put("fail_count", memory.failCount)
            put("created_at", memory.createdAt)
            put("updated_at", memory.updatedAt)
            put("last_used_at", memory.lastUsedAt)
        }
        return writableDatabase.insert("memories", null, values)
    }

    fun updateMemory(memory: MemoryEntity) {
        val values = ContentValues().apply {
            put("type", memory.type.name)
            put("package_name", memory.packageName)
            put("app_label", memory.appLabel)
            put("title", memory.title)
            put("content", memory.content)
            put("verified", if (memory.verified) 1 else 0)
            put("expired", if (memory.expired) 1 else 0)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update("memories", values, "id=?", arrayOf(memory.id.toString()))
    }

    fun deleteMemory(id: Long) = writableDatabase.delete("memories", "id=?", arrayOf(id.toString()))

    fun deleteMemoriesForPackage(pkg: String): Int =
        writableDatabase.delete("memories", "package_name=?", arrayOf(pkg))

    fun clearMemories() {
        writableDatabase.delete("memories", null, null)
        writableDatabase.delete("experiences", null, null)
    }

    fun memories(type: MemoryType? = null, query: String? = null, limit: Int = 500): List<MemoryEntity> {
        val args = mutableListOf<String>()
        val clauses = mutableListOf<String>()
        if (type != null) {
            clauses.add("type=?")
            args.add(type.name)
        }
        if (!query.isNullOrBlank()) {
            val like = "%${query.trim()}%"
            clauses.add("(title LIKE ? OR content LIKE ? OR package_name LIKE ? OR app_label LIKE ?)")
            repeat(4) { args.add(like) }
        }
        val where = clauses.joinToString(" AND ").ifBlank { null }
        return readableDatabase.query(
            "memories", null, where, args.toTypedArray(), null, null,
            "verified DESC, updated_at DESC", limit.toString()
        ).use { c -> buildList { while (c.moveToNext()) add(c.toMemory()) } }
    }

    fun memory(id: Long): MemoryEntity? = readableDatabase.query(
        "memories", null, "id=?", arrayOf(id.toString()), null, null, null
    ).use { c -> if (c.moveToFirst()) c.toMemory() else null }

    fun relevantMemories(keywords: List<String>, packageName: String?, limit: Int = 6): List<MemoryEntity> {
        val clauses = mutableListOf("expired=0")
        val args = mutableListOf<String>()
        if (!packageName.isNullOrBlank()) {
            clauses.add("(type='GENERAL' OR package_name IS NULL OR package_name=?)")
            args.add(packageName)
        } else {
            clauses.add("(type='GENERAL' OR package_name IS NULL)")
        }
        val clean = keywords.map { it.trim() }.filter { it.length >= 2 }.distinct().take(6)
        if (clean.isNotEmpty()) {
            val ors = clean.joinToString(" OR ") { "(title LIKE ? OR content LIKE ?)" }
            clauses.add("($ors)")
            clean.forEach { args.add("%$it%"); args.add("%$it%") }
        }
        return readableDatabase.query(
            "memories", null, clauses.joinToString(" AND "), args.toTypedArray(), null, null,
            "verified DESC, use_count DESC, updated_at DESC", limit.toString()
        ).use { c -> buildList { while (c.moveToNext()) add(c.toMemory()) } }
    }

    fun memoriesForPackage(pkg: String): List<MemoryEntity> = readableDatabase.query(
        "memories", null, "package_name=?", arrayOf(pkg), null, null, "updated_at DESC"
    ).use { c -> buildList { while (c.moveToNext()) add(c.toMemory()) } }

    fun markMemoryUsed(id: Long, success: Boolean) {
        val db = writableDatabase
        if (success) {
            db.execSQL(
                "UPDATE memories SET use_count=use_count+1, success_count=success_count+1, last_used_at=? WHERE id=?",
                arrayOf(System.currentTimeMillis(), id)
            )
        } else {
            db.execSQL(
                "UPDATE memories SET use_count=use_count+1, fail_count=fail_count+1, last_used_at=? WHERE id=?",
                arrayOf(System.currentTimeMillis(), id)
            )
        }
    }

    fun memoryCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM memories", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    // ----------------------------------------------------------- experiences

    fun insertExperience(exp: ExperienceEntity): Long {
        val values = ContentValues().apply {
            put("package_name", exp.packageName)
            put("task_type", exp.taskType)
            put("trigger_text", exp.trigger)
            put("method", exp.method)
            put("steps_json", exp.stepsJson)
            put("verified", if (exp.verified) 1 else 0)
            put("confidence", exp.confidence)
            put("success_count", exp.successCount)
            put("fail_count", exp.failCount)
            put("created_at", exp.createdAt)
            put("updated_at", exp.updatedAt)
        }
        return writableDatabase.insert("experiences", null, values)
    }

    fun allExperiences(): List<ExperienceEntity> = readableDatabase.query(
        "experiences", null, null, null, null, null, "confidence DESC, updated_at DESC"
    ).use { c -> buildList { while (c.moveToNext()) add(c.toExperience()) } }

    fun relevantExperiences(packageName: String?, keywords: List<String>, limit: Int = 3): List<ExperienceEntity> {
        val clauses = mutableListOf("verified=1")
        val args = mutableListOf<String>()
        if (!packageName.isNullOrBlank()) {
            clauses.add("(package_name IS NULL OR package_name=?)")
            args.add(packageName)
        }
        val clean = keywords.map { it.trim() }.filter { it.length >= 2 }.distinct().take(5)
        if (clean.isNotEmpty()) {
            val ors = clean.joinToString(" OR ") { "(trigger_text LIKE ? OR method LIKE ?)" }
            clauses.add("($ors)")
            clean.forEach { args.add("%$it%"); args.add("%$it%") }
        }
        return readableDatabase.query(
            "experiences", null, clauses.joinToString(" AND "), args.toTypedArray(), null, null,
            "confidence DESC, success_count DESC", limit.toString()
        ).use { c -> buildList { while (c.moveToNext()) add(c.toExperience()) } }
    }

    fun markExperienceUsed(id: Long, success: Boolean) {
        val column = if (success) "success_count" else "fail_count"
        writableDatabase.execSQL(
            "UPDATE experiences SET $column=$column+1, updated_at=? WHERE id=?",
            arrayOf(System.currentTimeMillis(), id)
        )
    }

    // --------------------------------------------------------------- mapping

    private fun Cursor.toApp() = AppEntity(
        id = long("id"),
        packageName = string("package_name"),
        label = string("label"),
        isSystem = int("is_system") == 1,
        versionName = optString("version_name"),
        lastSeen = long("last_seen"),
        archived = int("archived") == 1
    )

    private fun Cursor.toTask() = TaskEntity(
        id = long("id"),
        goal = string("goal"),
        status = string("status"),
        plan = optString("plan"),
        summary = optString("summary"),
        startedAt = long("started_at"),
        finishedAt = if (isNull(getColumnIndexOrThrow("finished_at"))) null else long("finished_at"),
        stepCount = int("step_count"),
        success = int("success") == 1
    )

    private fun Cursor.toStep() = TaskStepEntity(
        id = long("id"),
        taskId = long("task_id"),
        stepIndex = int("step_index"),
        action = string("action"),
        argsJson = optString("args_json"),
        resultJson = optString("result_json"),
        ok = int("ok") == 1,
        durationMs = long("duration_ms"),
        createdAt = long("created_at")
    )

    private fun Cursor.toMemory() = MemoryEntity(
        id = long("id"),
        type = MemoryType.from(string("type")),
        packageName = optString("package_name"),
        appLabel = optString("app_label"),
        title = string("title"),
        content = string("content"),
        verified = int("verified") == 1,
        expired = int("expired") == 1,
        useCount = int("use_count"),
        successCount = int("success_count"),
        failCount = int("fail_count"),
        createdAt = long("created_at"),
        updatedAt = long("updated_at"),
        lastUsedAt = long("last_used_at")
    )

    private fun Cursor.toExperience() = ExperienceEntity(
        id = long("id"),
        packageName = optString("package_name"),
        taskType = optString("task_type"),
        trigger = string("trigger_text"),
        method = string("method"),
        stepsJson = optString("steps_json"),
        verified = int("verified") == 1,
        confidence = getDouble(getColumnIndexOrThrow("confidence")).toFloat(),
        successCount = int("success_count"),
        failCount = int("fail_count"),
        createdAt = long("created_at"),
        updatedAt = long("updated_at")
    )

    private fun Cursor.string(name: String): String = getString(getColumnIndexOrThrow(name)) ?: ""

    private fun Cursor.optString(name: String): String? {
        val index = getColumnIndexOrThrow(name)
        return if (isNull(index)) null else getString(index)
    }

    private fun Cursor.int(name: String): Int = getInt(getColumnIndexOrThrow(name))

    private fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))

    companion object {
        const val NAME = "lingtouch.db"
        const val VERSION = 1
    }
}
