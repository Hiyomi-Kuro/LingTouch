package com.kaori.lingtouch.chat

import android.content.ContentValues
import android.database.Cursor
import com.kaori.lingtouch.memory.LingTouchDb

/**
 * Persistence for the chat home: sessions, messages and one editable memory document per mode.
 * All calls are synchronous and are invoked from Dispatchers.IO by the UI/engine layer.
 */
class ChatStore(private val db: LingTouchDb) {

    fun createSession(
        mode: ChatMode,
        title: String,
        model: String,
        workUri: String,
        workName: String,
        taskId: Long = -1
    ): ChatSession {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("mode", mode.name)
            put("title", title.take(120).ifBlank { "新对话" })
            put("model", model.trim())
            put("work_uri", workUri)
            put("work_name", workName)
            put("task_id", taskId)
            put("created_at", now)
            put("updated_at", now)
        }
        val id = db.writableDatabase.insert("chat_sessions", null, values)
        return ChatSession(id, mode, title, model, workUri, workName, taskId, now, now)
    }

    fun updateSession(session: ChatSession) {
        val values = ContentValues().apply {
            put("mode", session.mode.name)
            put("title", session.title.take(120).ifBlank { "新对话" })
            put("model", session.model.trim())
            put("work_uri", session.workUri)
            put("work_name", session.workName)
            put("task_id", session.taskId)
            put("updated_at", session.updatedAt)
        }
        db.writableDatabase.update("chat_sessions", values, "id=?", arrayOf(session.id.toString()))
    }

    fun touch(id: Long, title: String? = null) {
        val values = ContentValues().apply {
            put("updated_at", System.currentTimeMillis())
            if (title != null) put("title", title.take(120))
        }
        db.writableDatabase.update("chat_sessions", values, "id=?", arrayOf(id.toString()))
    }

    fun setTaskId(sessionId: Long, taskId: Long) {
        val values = ContentValues().apply {
            put("task_id", taskId)
            put("updated_at", System.currentTimeMillis())
        }
        db.writableDatabase.update("chat_sessions", values, "id=?", arrayOf(sessionId.toString()))
    }

    fun session(id: Long): ChatSession? {
        if (id <= 0) return null
        return db.readableDatabase.rawQuery(
            "SELECT * FROM chat_sessions WHERE id=? LIMIT 1",
            arrayOf(id.toString())
        ).use { cursor -> if (cursor.moveToFirst()) readSession(cursor) else null }
    }

    fun latest(mode: ChatMode): ChatSession? = db.readableDatabase.rawQuery(
        "SELECT * FROM chat_sessions WHERE mode=? ORDER BY updated_at DESC LIMIT 1",
        arrayOf(mode.name)
    ).use { cursor -> if (cursor.moveToFirst()) readSession(cursor) else null }

    fun sessions(mode: ChatMode, limit: Int = 100): List<ChatSession> =
        db.readableDatabase.rawQuery(
            "SELECT * FROM chat_sessions WHERE mode=? ORDER BY updated_at DESC LIMIT ?",
            arrayOf(mode.name, limit.toString())
        ).use { cursor ->
            val list = ArrayList<ChatSession>()
            while (cursor.moveToNext()) list.add(readSession(cursor))
            list
        }

    fun deleteSession(id: Long) {
        if (id <= 0) return
        db.writableDatabase.delete("chat_sessions", "id=?", arrayOf(id.toString()))
    }

    fun addMessage(sessionId: Long, role: String, content: String, reasoning: String = "", meta: String? = null): ChatMessage {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("session_id", sessionId)
            put("role", role)
            put("content", content)
            put("reasoning", reasoning)
            put("meta", meta)
            put("created_at", now)
        }
        val id = db.writableDatabase.insert("chat_messages", null, values)
        touch(sessionId)
        return ChatMessage(id, sessionId, role, content, reasoning, meta, now)
    }

    fun updateMessage(id: Long, content: String, reasoning: String, meta: String? = null) {
        if (id <= 0) return
        val values = ContentValues().apply {
            put("content", content)
            put("reasoning", reasoning)
            put("meta", meta)
        }
        db.writableDatabase.update("chat_messages", values, "id=?", arrayOf(id.toString()))
    }

    fun messages(sessionId: Long): List<ChatMessage> = db.readableDatabase.rawQuery(
        "SELECT * FROM chat_messages WHERE session_id=? ORDER BY id ASC",
        arrayOf(sessionId.toString())
    ).use { cursor ->
        val list = ArrayList<ChatMessage>()
        while (cursor.moveToNext()) list.add(readMessage(cursor))
        list
    }

    fun clearMessages(sessionId: Long) {
        db.writableDatabase.delete("chat_messages", "session_id=?", arrayOf(sessionId.toString()))
        touch(sessionId)
    }

    fun modeMemory(mode: ChatMode): String = db.readableDatabase.rawQuery(
        "SELECT content FROM mode_memory WHERE mode=? LIMIT 1",
        arrayOf(mode.name)
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else "" }

    fun setModeMemory(mode: ChatMode, content: String) {
        val values = ContentValues().apply {
            put("mode", mode.name)
            put("content", content)
            put("updated_at", System.currentTimeMillis())
        }
        db.writableDatabase.insertWithOnConflict(
            "mode_memory", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun readSession(cursor: Cursor): ChatSession = ChatSession(
        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
        mode = ChatMode.from(cursor.getString(cursor.getColumnIndexOrThrow("mode"))),
        title = cursor.getString(cursor.getColumnIndexOrThrow("title")).orEmpty(),
        model = cursor.getString(cursor.getColumnIndexOrThrow("model")).orEmpty(),
        workUri = cursor.getString(cursor.getColumnIndexOrThrow("work_uri")).orEmpty(),
        workName = cursor.getString(cursor.getColumnIndexOrThrow("work_name")).orEmpty(),
        taskId = cursor.getLong(cursor.getColumnIndexOrThrow("task_id")),
        createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
        updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
    )

    private fun readMessage(cursor: Cursor): ChatMessage = ChatMessage(
        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
        sessionId = cursor.getLong(cursor.getColumnIndexOrThrow("session_id")),
        role = cursor.getString(cursor.getColumnIndexOrThrow("role")).orEmpty(),
        content = cursor.getString(cursor.getColumnIndexOrThrow("content")).orEmpty(),
        reasoning = cursor.getString(cursor.getColumnIndexOrThrow("reasoning")).orEmpty(),
        meta = cursor.getString(cursor.getColumnIndexOrThrow("meta")),
        createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))
    )
}
