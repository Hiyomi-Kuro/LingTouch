package com.kaori.lingtouch.storage

import android.content.Context
import com.kaori.lingtouch.util.Logx
import java.io.File
import java.util.UUID

/**
 * Owns every transient artifact created while executing a task: screenshots, OCR
 * bitmaps, request dumps. Files live under cacheDir/agent/<taskId>/ and are deleted
 * when the task ends (success, failure, cancellation, timeout or crash recovery).
 */
class TempResourceManager(context: Context) {
    private val root = File(context.cacheDir, "agent")

    fun newTaskId(): Long = System.currentTimeMillis()

    fun taskDir(taskId: Long): File = File(root, "task_$taskId").apply { mkdirs() }

    fun file(taskId: Long, prefix: String, extension: String = "jpg"): File {
        val safePrefix = prefix.replace(Regex("[^A-Za-z0-9_-]"), "_").take(24).ifBlank { "file" }
        return File(taskDir(taskId), "${safePrefix}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.$extension")
    }

    fun cleanupTask(taskId: Long): Long {
        val dir = File(root, "task_$taskId")
        return if (dir.exists()) deleteRecursively(dir) else 0L
    }

    fun cleanupAll(): Long {
        if (!root.exists()) return 0L
        val freed = totalSize(root)
        root.listFiles()?.forEach { deleteRecursively(it) }
        return freed
    }

    /** Removes stale task folders that are not part of a still-running task. */
    fun pruneOrphans(activeTaskIds: Set<Long>) {
        root.listFiles()?.forEach { child ->
            val name = child.name
            val id = name.removePrefix("task_").toLongOrNull()
            if (child.isDirectory && (id == null || id !in activeTaskIds)) {
                Logx.d("Temp", "清理孤儿任务目录 ${child.name}")
                deleteRecursively(child)
            }
        }
    }

    fun totalSize(): Long = totalSize(root)

    private fun totalSize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf { totalSize(it) } ?: 0L
    }

    private fun deleteRecursively(file: File): Long {
        if (!file.exists()) return 0L
        var freed = 0L
        if (file.isDirectory) {
            file.listFiles()?.forEach { freed += deleteRecursively(it) }
        } else {
            freed += file.length()
        }
        file.delete()
        return freed
    }
}
