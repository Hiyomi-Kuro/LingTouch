package com.kaori.lingtouch.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkFileToolsTest {

    @Test
    fun `normalize collapses dots and slashes`() {
        assertEquals("app/src/Main.kt", WorkFileTools.normalize("/app/./src//Main.kt"))
        assertEquals("", WorkFileTools.normalize("."))
        assertEquals("a/b", WorkFileTools.normalize("a\\b"))
    }

    @Test
    fun `parent traversal is rejected`() {
        val failed = runCatching { WorkFileTools.normalize("../secret") }.isFailure
        assertTrue(failed)
    }

    @Test
    fun `count occurrences`() {
        assertEquals(2, WorkFileTools.countOccurrences("a-b-a", "a"))
        assertEquals(0, WorkFileTools.countOccurrences("abc", "z"))
    }

    @Test
    fun `binary detection uses nul bytes`() {
        assertTrue(WorkFileTools.looksBinary(byteArrayOf(1, 2, 0, 4)))
        assertFalse(WorkFileTools.looksBinary("hello".toByteArray()))
    }

    @Test
    fun `mime matches requested extension`() {
        assertEquals("text/markdown", WorkFileTools.mimeFor("AGENTS.md"))
        assertEquals("text/x-kotlin", WorkFileTools.mimeFor("Main.kt"))
        assertEquals("application/json", WorkFileTools.mimeFor("config.json"))
        assertEquals("image/png", WorkFileTools.mimeFor("icon.png"))
        assertEquals("application/octet-stream", WorkFileTools.mimeFor("data.unknown"))
        assertEquals("text/plain", WorkFileTools.mimeFor("Makefile"))
        assertEquals("text/plain", WorkFileTools.mimeFor(".gitignore"))
    }

    @Test
    fun `normalize keeps exact file names and extensions`() {
        assertEquals("AGENTS.md", WorkFileTools.normalize("AGENTS.md"))
        assertEquals("docs/AGENTS.md", WorkFileTools.normalize("./docs/AGENTS.md"))
        assertEquals(".gitignore", WorkFileTools.normalize(".gitignore"))
    }
}
