package com.kaori.lingtouch.chat

/** The three home-screen modes. CONTROL drives the phone, CHAT is a plain assistant, WORK edits files. */
enum class ChatMode {
    CONTROL, CHAT, WORK;

    companion object {
        fun from(value: String?): ChatMode = entries.firstOrNull { it.name == value } ?: CHAT
    }
}

data class ChatSession(
    val id: Long = 0,
    val mode: ChatMode,
    val title: String,
    /** Blank means "follow the configured default model". */
    val model: String = "",
    val workUri: String = "",
    val workName: String = "",
    val taskId: Long = -1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class ChatMessage(
    val id: Long = 0,
    val sessionId: Long,
    val role: String,
    val content: String,
    val reasoning: String = "",
    /** Optional JSON metadata: steps, refs, status, task id. */
    val meta: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    val isUser: Boolean get() = role == "user"
    val isAssistant: Boolean get() = role == "assistant"
    val isTool: Boolean get() = role == "tool"
}

/** A user-picked @ reference. [kind] is work-file, app, or memory. */
data class ChatRef(
    val kind: String,
    val id: String,
    val label: String
)
