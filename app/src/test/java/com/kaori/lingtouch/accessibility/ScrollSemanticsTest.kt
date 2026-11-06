package com.kaori.lingtouch.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollSemanticsTest {

    @Test
    fun `up scrolls backward`() {
        assertEquals(ScrollAxis.BACKWARD, ScrollSemantics.resolve("up"))
    }

    @Test
    fun `down scrolls forward and not backward`() {
        assertEquals(ScrollAxis.FORWARD, ScrollSemantics.resolve("down"))
    }

    @Test
    fun `backward aliases map to backward`() {
        assertEquals(ScrollAxis.BACKWARD, ScrollSemantics.resolve("backward"))
        assertEquals(ScrollAxis.BACKWARD, ScrollSemantics.resolve("BACK"))
    }

    @Test
    fun `left and right map to horizontal axes`() {
        assertEquals(ScrollAxis.LEFT, ScrollSemantics.resolve("left"))
        assertEquals(ScrollAxis.RIGHT, ScrollSemantics.resolve("right"))
    }

    @Test
    fun `unknown direction falls back to forward`() {
        assertEquals(ScrollAxis.FORWARD, ScrollSemantics.resolve("sideways"))
    }

    @Test
    fun `direction is case and whitespace tolerant`() {
        assertEquals(ScrollAxis.FORWARD, ScrollSemantics.resolve("  DOWN  "))
    }
}
