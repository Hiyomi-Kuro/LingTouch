package com.kaori.lingtouch.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopDetectorTest {

    @Test
    fun `three identical actions are reported as repetition`() {
        val detector = LoopDetector()
        assertNull(detector.onAction("tap:A"))
        assertNull(detector.onAction("tap:A"))
        val signal = detector.onAction("tap:A")
        assertEquals(LoopKind.REPEAT_ACTION, signal?.kind)
        assertEquals(3, signal?.count)
    }

    @Test
    fun `different action resets repetition count`() {
        val detector = LoopDetector()
        detector.onAction("tap:A")
        detector.onAction("tap:A")
        assertNull(detector.onAction("tap:B"))
        assertNull(detector.onAction("tap:B"))
    }

    @Test
    fun `unchanged screen is reported as stagnation`() {
        val detector = LoopDetector(stagnantThreshold = 2)
        assertNull(detector.onScreen("screen-1"))
        assertNull(detector.onScreen("screen-1"))
        val signal = detector.onScreen("screen-1")
        assertEquals(LoopKind.STAGNANT_SCREEN, signal?.kind)
    }

    @Test
    fun `screen changes reset stagnation`() {
        val detector = LoopDetector(stagnantThreshold = 2)
        detector.onScreen("a")
        detector.onScreen("a")
        assertNull(detector.onScreen("b"))
    }

    @Test
    fun `ping pong between two screens is reported`() {
        val detector = LoopDetector()
        detector.onScreen("A")
        detector.onScreen("B")
        detector.onScreen("A")
        assertNull(detector.onScreen("B"))
        val signal = detector.onScreen("A")
        assertEquals(LoopKind.OSCILLATION, signal?.kind)
        assertTrue(signal!!.detail.contains("来回"))
    }

    @Test
    fun `three distinct screens are not oscillation`() {
        val detector = LoopDetector()
        assertNull(detector.onScreen("A"))
        assertNull(detector.onScreen("B"))
        assertNull(detector.onScreen("C"))
        assertNull(detector.onScreen("A"))
        assertNull(detector.onScreen("B"))
        assertNull(detector.onScreen("C"))
    }

    @Test
    fun `reset clears history`() {
        val detector = LoopDetector()
        detector.onAction("tap:A")
        detector.onAction("tap:A")
        detector.onAction("tap:A")
        detector.reset()
        assertNull(detector.onAction("tap:A"))
        assertNull(detector.onScreen("X"))
    }
}
