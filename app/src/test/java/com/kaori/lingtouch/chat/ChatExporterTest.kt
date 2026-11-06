package com.kaori.lingtouch.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatExporterTest {

    @Test
    fun `export contains complete reasoning and step list`() {
        val session = ChatSession(
            id = 7,
            mode = ChatMode.WORK,
            title = "fix build",
            model = "glm-5.3-flash",
            workUri = "content://tree",
            workName = "LingTouch"
        )
        val messages = listOf(
            ChatMessage(1, 7, "user", "please read a.kt"),
            ChatMessage(
                2, 7, "assistant", "done",
                reasoning = "first inspect then edit",
                meta = """{"steps":[{"action":"read_file","detail":"a.kt","ok":true,"message":"ok"}]}"""
            )
        )
        val text = ChatExporter.build(
            mode = ChatMode.WORK,
            session = session,
            model = "glm-5.3-flash",
            messages = messages,
            memory = "prefer Kotlin"
        )
        assertTrue(text.contains("fix build"))
        assertTrue(text.contains("first inspect then edit"))
        assertTrue(text.contains("read_file"))
        assertTrue(text.contains("prefer Kotlin"))
        assertTrue(text.contains("LingTouch"))
    }

    @Test
    fun `steps parser tolerates bad metadata`() {
        assertEquals(emptyList<String>(), ChatExporter.stepsOf(null))
        assertEquals(emptyList<String>(), ChatExporter.stepsOf("not json"))
        assertEquals(1, ChatExporter.stepsOf("""{"steps":[{"action":"x","ok":false}]}""").size)
    }

    @Test
    fun `filename is mode scoped`() {
        val name = ChatExporter.fileName(ChatMode.CHAT, 0L)
        assertTrue(name.startsWith("lingtouch-chat-"))
        assertTrue(name.endsWith(".md"))
    }
}
