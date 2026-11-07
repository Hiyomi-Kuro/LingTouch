package com.kaori.lingtouch.chat

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Scoped-storage file access for WORK mode. The user grants one SAF tree; every path used
 * by the model is relative to that tree and `..` is rejected, so the agent can never escape
 * the chosen working directory.
 */
class WorkFileTools(private val context: Context, private val treeUri: Uri) {

    data class WorkFile(
        val path: String,
        val name: String,
        val isDir: Boolean,
        val size: Long,
        val mime: String
    )

    private data class Entry(
        val docId: String,
        val name: String,
        val mime: String,
        val size: Long,
        val modified: Long,
        val uri: Uri
    ) {
        val isDir: Boolean get() = mime == Document.MIME_TYPE_DIR
    }

    private val resolver get() = context.contentResolver
    private val rootId: String = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
        .getOrElse { throw IllegalArgumentException("工作目录授权无效，请重新选择") }

    suspend fun list(path: String): List<WorkFile> = withContext(Dispatchers.IO) {
        val dir = resolve(path)
        if (!dir.isDir) throw IllegalStateException("不是目录：$path")
        children(dir.docId)
            .sortedWith(compareByDescending<Entry> { it.isDir }.thenBy { it.name.lowercase() })
            .map { toWorkFile(it, normalize(path)) }
    }

    /** Breadth-first listing used by the @ picker, skipping heavy generated directories. */
    suspend fun walk(max: Int = 400): List<WorkFile> = withContext(Dispatchers.IO) {
        val result = ArrayList<WorkFile>()
        val queue = ArrayDeque<Pair<String, String>>()
        queue.add("" to rootId)
        var visited = 0
        while (queue.isNotEmpty() && result.size < max && visited < 1200) {
            val (path, docId) = queue.removeFirst()
            visited++
            val entries = runCatching { children(docId) }.getOrElse { emptyList() }
            for (entry in entries.sortedBy { it.name.lowercase() }) {
                val childPath = if (path.isBlank()) entry.name else "$path/${entry.name}"
                if (entry.isDir) {
                    if (entry.name.lowercase() !in SKIP_DIRS) queue.add(childPath to entry.docId)
                } else {
                    result.add(toWorkFile(entry, path))
                    if (result.size >= max) break
                }
            }
        }
        result
    }

    suspend fun read(path: String, encoding: String = "auto", maxBytes: Int = 240_000): String =
        withContext(Dispatchers.IO) {
            val entry = resolve(path)
            if (entry.isDir) throw IllegalStateException("$path 是目录，请使用 list_files")
            val bytes = readLimited(entry, maxBytes)
            val truncated = entry.size > bytes.size
            val suffix = if (truncated) "\n\n[已截断：原文件 ${entry.size} 字节]" else ""
            when (encoding.trim().lowercase()) {
                "base64", "raw" -> {
                    if (truncated) {
                        throw IllegalStateException("二进制文件超过单次读取上限，请增大 max_bytes（当前 ${bytes.size} 字节）")
                    }
                    Base64.encodeToString(bytes, Base64.NO_WRAP)
                }
                "auto" -> {
                    if (!looksBinary(bytes)) {
                        String(bytes, Charsets.UTF_8) + suffix
                    } else {
                        if (truncated) {
                            throw IllegalStateException(
                                "二进制文件超过单次读取上限（${entry.size} 字节），请增大 max_bytes 或改用 file_info"
                            )
                        }
                        "[二进制文件：${entry.size} 字节，${entry.mime}；以下为 base64]\n" +
                            Base64.encodeToString(bytes, Base64.NO_WRAP)
                    }
                }
                else -> {
                    if (looksBinary(bytes)) {
                        throw IllegalStateException(
                            "文件是二进制（${entry.size} 字节，${entry.mime}）。请用 encoding=base64 或 encoding=auto 读取，或改用 file_info 查看元数据"
                        )
                    }
                    String(bytes, Charsets.UTF_8) + suffix
                }
            }
        }

    suspend fun readForReference(path: String, maxChars: Int = 8_000): String =
        withContext(Dispatchers.IO) {
            runCatching {
                val entry = resolve(path)
                if (entry.isDir) {
                    "目录 $path"
                } else {
                    val bytes = readLimited(entry, 64_000)
                    val text = if (looksBinary(bytes)) {
                        "二进制文件，base64 前 ${minOf(bytes.size, 2000)} 字节：\n" +
                            Base64.encodeToString(bytes.copyOf(minOf(bytes.size, 2000)), Base64.NO_WRAP)
                    } else {
                        String(bytes, Charsets.UTF_8)
                    }
                    if (text.length > maxChars) text.take(maxChars) + "\n…（已截断）" else text
                }
            }.getOrElse { "无法读取 $path：${it.message}" }
        }

    suspend fun write(path: String, content: String, encoding: String = "text"): String =
        withContext(Dispatchers.IO) {
            val normalized = normalize(path)
            if (normalized.isBlank()) throw IllegalArgumentException("文件路径不能为空")
            val parentPath = normalized.substringBeforeLast('/', "")
            val name = normalized.substringAfterLast('/')
            if (name.isBlank()) throw IllegalArgumentException("文件路径无效")
            val parent = ensureDirectory(parentPath)
            val existing = children(parent.docId).firstOrNull { it.name == name }
            if (existing?.isDir == true) throw IllegalStateException("$path 是目录")
            val bytes = when (encoding.lowercase()) {
                "base64" -> runCatching { Base64.decode(content, Base64.DEFAULT) }
                    .getOrElse { throw IllegalArgumentException("base64 内容无效：${it.message}") }
                else -> content.toByteArray(Charsets.UTF_8)
            }
            if (bytes.size > 4 * 1024 * 1024) throw IllegalStateException("单次写入不能超过 4MB")
            val target = existing?.uri ?: createFile(parent, name, path)
            val stream = runCatching { resolver.openOutputStream(target, "wt") }.getOrNull()
                ?: resolver.openOutputStream(target, "w")
                ?: throw IllegalStateException("无法打开 $path 写入")
            stream.use { it.write(bytes) }
            "已写入 $path（${bytes.size} 字节）"
        }

    suspend fun edit(path: String, find: String, replace: String, all: Boolean = false): String =
        withContext(Dispatchers.IO) {
            if (find.isEmpty()) throw IllegalArgumentException("find 不能为空")
            val current = read(path, "text", 2 * 1024 * 1024)
            val count = countOccurrences(current, find)
            if (count == 0) throw IllegalStateException("文件中找不到指定文本")
            val updated = if (all) current.replace(find, replace) else current.replaceFirst(find, replace)
            val written = write(path, updated, "text")
            "$written；替换 ${if (all) count else 1} 处"
        }

    suspend fun delete(path: String): String = withContext(Dispatchers.IO) {
        val entry = resolve(path)
        if (entry.isDir && children(entry.docId).isNotEmpty()) {
            throw IllegalStateException("目录非空，拒绝递归删除：$path")
        }
        val ok = runCatching { DocumentsContract.deleteDocument(resolver, entry.uri) }.getOrDefault(false)
        if (!ok) throw IllegalStateException("系统拒绝删除 $path")
        "已删除 $path"
    }

    suspend fun info(path: String): String = withContext(Dispatchers.IO) {
        val entry = resolve(path)
        "path=$path\nname=${entry.name}\ntype=${if (entry.isDir) "directory" else "file"}" +
            "\nmime=${entry.mime}\nsize=${entry.size}\nmodified=${entry.modified}"
    }

    suspend fun search(query: String, path: String = "", max: Int = 80): String =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) throw IllegalArgumentException("query 不能为空")
            val start = resolve(path)
            if (!start.isDir) throw IllegalArgumentException("$path 不是目录")
            val output = StringBuilder()
            val queue = ArrayDeque<Pair<String, String>>()
            queue.add(normalize(path) to start.docId)
            var files = 0
            var matches = 0
            while (queue.isNotEmpty() && matches < max && files < 500) {
                val (dirPath, docId) = queue.removeFirst()
                val entries = runCatching { children(docId) }.getOrElse { emptyList() }
                for (entry in entries.sortedBy { it.name.lowercase() }) {
                    val childPath = if (dirPath.isBlank()) entry.name else "$dirPath/${entry.name}"
                    if (entry.isDir) {
                        if (entry.name.lowercase() !in SKIP_DIRS) queue.add(childPath to entry.docId)
                        continue
                    }
                    if (entry.size > 600_000 || looksBinaryMime(entry.mime)) continue
                    files++
                    val text = runCatching { String(readLimited(entry, 300_000), Charsets.UTF_8) }.getOrNull() ?: continue
                    if (!text.contains(q, ignoreCase = true)) continue
                    text.lineSequence().forEachIndexed { index, line ->
                        if (matches >= max) return@forEachIndexed
                        if (line.contains(q, ignoreCase = true)) {
                            output.append(childPath).append(':').append(index + 1).append(": ")
                                .append(line.trim().take(240)).append('\n')
                            matches++
                        }
                    }
                }
            }
            if (matches == 0) "未找到包含 \"$q\" 的文本文件（已扫描 $files 个文件）"
            else output.append("\n共 $matches 处匹配（已扫描 $files 个文件）").toString()
        }

    // -------------------------------------------------------------- internals

    private fun resolve(path: String): Entry {
        val normalized = normalize(path)
        var current = rootEntry()
        if (normalized.isEmpty()) return current
        for (segment in normalized.split('/')) {
            if (!current.isDir) throw IllegalStateException("${current.name} 不是目录")
            current = children(current.docId).firstOrNull { it.name == segment }
                ?: throw IllegalStateException("找不到路径：$path（缺少 $segment）")
        }
        return current
    }

    private fun rootEntry(): Entry {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val projection = arrayOf(
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED
        )
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return Entry(
                    docId = rootId,
                    name = cursor.getString(0).orEmpty().ifBlank { "工作目录" },
                    mime = Document.MIME_TYPE_DIR,
                    size = cursor.getLong(2),
                    modified = cursor.getLong(3),
                    uri = uri
                )
            }
        }
        return Entry(rootId, "工作目录", Document.MIME_TYPE_DIR, 0, 0, uri)
    }

    private fun children(docId: String): List<Entry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED
        )
        val list = ArrayList<Entry>()
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val childId = cursor.getString(0).orEmpty()
                list.add(
                    Entry(
                        docId = childId,
                        name = cursor.getString(1).orEmpty(),
                        mime = cursor.getString(2).orEmpty(),
                        size = cursor.getLong(3),
                        modified = cursor.getLong(4),
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                    )
                )
            }
        } ?: throw IllegalStateException("无法读取工作目录（授权可能已失效）")
        return list
    }

    /**
     * Creates a document with the exact requested display name.
     *
     * SAF providers derive the stored extension from the MIME type, so a generic text/plain
     * for `AGENTS.md` produced `AGENTS.md.txt`. We pass an extension-matched MIME where
     * possible, then verify the real display name reported by the provider and rename if
     * it still rewrote the name.
     */
    private fun createFile(parent: Entry, name: String, requestedPath: String): Uri {
        val created = DocumentsContract.createDocument(resolver, parent.uri, mimeFor(name), name)
            ?: throw IllegalStateException("系统拒绝创建文件 $requestedPath（工作目录可能只读）")
        val actualName = displayNameOf(created)
        if (actualName == name) return created
        if (actualName.isBlank()) return created

        val renamed = runCatching { DocumentsContract.renameDocument(resolver, created, name) }.getOrNull()
        if (renamed != null && displayNameOf(renamed) == name) return renamed

        // Some providers rename successfully but return null; look the document up by name.
        val exact = runCatching { children(parent.docId).firstOrNull { it.name == name } }.getOrNull()
        if (exact != null) return exact.uri

        val cleanup = renamed ?: created
        runCatching { DocumentsContract.deleteDocument(resolver, cleanup) }
        throw IllegalStateException(
            "系统把文件名改写成了 \"$actualName\"（期望 \"$name\"）。已停止写入，请重试或更换工作目录"
        )
    }

    private fun displayNameOf(uri: Uri): String =
        runCatching {
            resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
            }.orEmpty()
        }.getOrDefault("")

    private fun ensureDirectory(path: String): Entry {
        val normalized = normalize(path)
        var current = rootEntry()
        if (normalized.isEmpty()) return current
        for (segment in normalized.split('/')) {
            val existing = children(current.docId).firstOrNull { it.name == segment }
            current = if (existing != null) {
                if (!existing.isDir) throw IllegalStateException("$segment 不是目录")
                existing
            } else {
                val uri = DocumentsContract.createDocument(resolver, current.uri, Document.MIME_TYPE_DIR, segment)
                    ?: throw IllegalStateException("无法创建目录 $segment（工作目录可能只读）")
                Entry(
                    docId = DocumentsContract.getDocumentId(uri),
                    name = segment,
                    mime = Document.MIME_TYPE_DIR,
                    size = 0,
                    modified = System.currentTimeMillis(),
                    uri = uri
                )
            }
        }
        return current
    }

    private fun readLimited(entry: Entry, maxBytes: Int): ByteArray {
        resolver.openInputStream(entry.uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                val take = minOf(read, maxBytes - total)
                if (take > 0) output.write(buffer, 0, take)
                total += read
                if (total >= maxBytes) break
            }
            return output.toByteArray()
        }
        throw IllegalStateException("无法读取文件")
    }

    private fun toWorkFile(entry: Entry, parentPath: String): WorkFile = WorkFile(
        path = if (parentPath.isBlank()) entry.name else "$parentPath/${entry.name}",
        name = entry.name,
        isDir = entry.isDir,
        size = entry.size,
        mime = entry.mime
    )

    companion object {
        private val SKIP_DIRS = setOf(
            ".git", ".gradle", ".kotlin", ".idea", ".tmp", "build", "node_modules", ".cxx", "dist", "out"
        )

        fun normalize(path: String): String {
            val parts = path.replace('\\', '/').trim().split('/')
                .filter { it.isNotEmpty() && it != "." }
            require(parts.none { it == ".." }) { "路径不能包含 .." }
            return parts.joinToString("/")
        }

        fun countOccurrences(haystack: String, needle: String): Int {
            if (needle.isEmpty()) return 0
            var count = 0
            var index = haystack.indexOf(needle)
            while (index >= 0) {
                count++
                index = haystack.indexOf(needle, index + needle.length)
            }
            return count
        }

        fun looksBinary(bytes: ByteArray): Boolean {
            val limit = minOf(bytes.size, 8_192)
            for (index in 0 until limit) {
                if (bytes[index] == 0.toByte()) return true
            }
            return false
        }

        private fun looksBinaryMime(mime: String): Boolean =
            mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/") ||
                mime.contains("zip") || mime.contains("octet-stream") || mime.contains("pdf")

        /**
         * MIME types that match the requested extension, so SAF providers keep the exact
         * filename. A generic text/plain makes ExternalStorageProvider append `.txt`
         * (AGENTS.md -> AGENTS.md.txt); an extension-specific type does not.
         */
        private val MIME_BY_EXTENSION = mapOf(
            "txt" to "text/plain",
            "text" to "text/plain",
            "log" to "text/plain",
            "md" to "text/markdown",
            "markdown" to "text/markdown",
            "json" to "application/json",
            "jsonl" to "application/x-ndjson",
            "xml" to "application/xml",
            "html" to "text/html",
            "htm" to "text/html",
            "css" to "text/css",
            "csv" to "text/csv",
            "tsv" to "text/tab-separated-values",
            "js" to "text/javascript",
            "mjs" to "text/javascript",
            "cjs" to "text/javascript",
            "jsx" to "text/jsx",
            "ts" to "text/x-typescript",
            "tsx" to "text/x-typescript",
            "kt" to "text/x-kotlin",
            "kts" to "text/x-kotlin",
            "java" to "text/x-java-source",
            "gradle" to "text/x-gradle",
            "properties" to "text/x-java-properties",
            "yml" to "text/yaml",
            "yaml" to "text/yaml",
            "toml" to "text/x-toml",
            "ini" to "text/x-ini",
            "cfg" to "text/x-ini",
            "conf" to "text/plain",
            "sh" to "application/x-sh",
            "bash" to "application/x-sh",
            "zsh" to "application/x-sh",
            "py" to "text/x-python",
            "rb" to "text/x-ruby",
            "go" to "text/x-go",
            "rs" to "text/x-rust",
            "c" to "text/x-c",
            "h" to "text/x-c",
            "cc" to "text/x-c++src",
            "cpp" to "text/x-c++src",
            "hpp" to "text/x-c++hdr",
            "sql" to "application/sql",
            "svg" to "image/svg+xml",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "webp" to "image/webp",
            "gif" to "image/gif",
            "bmp" to "image/bmp",
            "ico" to "image/x-icon",
            "heic" to "image/heic",
            "pdf" to "application/pdf",
            "zip" to "application/zip",
            "apk" to "application/vnd.android.package-archive",
            "jar" to "application/java-archive",
            "mp3" to "audio/mpeg",
            "wav" to "audio/wav",
            "m4a" to "audio/mp4",
            "ogg" to "audio/ogg",
            "mp4" to "video/mp4",
            "webm" to "video/webm",
            "ttf" to "font/ttf",
            "otf" to "font/otf",
            "woff" to "font/woff",
            "woff2" to "font/woff2",
            "wasm" to "application/wasm"
        )

        private val TEXT_FILE_NAMES = setOf(
            "makefile", "dockerfile", "containerfile", "license", "licence", "readme",
            "changelog", "gemfile", "rakefile", "procfile", "justfile", "workspace",
            "build", ".gitignore", ".gitattributes", ".editorconfig", ".env",
            ".npmrc", ".prettierrc", ".babelrc", ".dockerignore", ".gitmodules"
        )

        fun mimeFor(name: String): String {
            val dot = name.lastIndexOf('.')
            val extension = if (dot > 0 && dot < name.length - 1) {
                name.substring(dot + 1).lowercase()
            } else {
                ""
            }
            if (extension.isNotEmpty()) {
                return MIME_BY_EXTENSION[extension] ?: "application/octet-stream"
            }
            return if (name.lowercase() in TEXT_FILE_NAMES) "text/plain" else "application/octet-stream"
        }

        fun displayName(context: Context, uri: Uri): String {
            val treeId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
                ?: return "工作目录"
            val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, treeId)
            runCatching {
                context.contentResolver.query(
                    docUri,
                    arrayOf(Document.COLUMN_DISPLAY_NAME),
                    null, null, null
                )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
            return uri.lastPathSegment ?: "工作目录"
        }
    }
}
