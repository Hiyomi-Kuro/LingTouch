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
}
