package io.embodit.toomuch.domain

import io.embodit.toomuch.domain.usecase.LevelGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** native-window-move §7 item 9: the window opens only with the head level (D9). */
class LevelGateTest {
    @Test
    fun levelHeadOpensAfterTheDwell() {
        val g = LevelGate()
        g.ask("stage", 0)
        assertNull(g.step(0, 2f))
        assertNull(g.step(299, 2f))
        assertEquals("stage", g.step(300, 2f))
        assertFalse(g.waiting)
    }

    @Test
    fun tiltedHeadWaitsAndAsksToLookAhead() {
        val g = LevelGate()
        g.ask("stage", 0)
        for (t in 0L..10_000L step 100L) assertNull("looking down 30 at $t", g.step(t, -30f))
        assertTrue(g.hintWanted(-30f))
        assertTrue(g.hintWanted(30f))
        assertFalse(g.hintWanted(5f))
        assertTrue(g.waiting)
    }

    @Test
    fun levellingAfterATiltOpensOnlyAfterTheDwell() {
        val g = LevelGate()
        g.ask("recall", 0)
        assertNull(g.step(0, 30f))
        assertNull(g.step(1000, 4f))
        assertNull(g.step(1200, 15f))
        assertNull(g.step(1300, 0f))
        assertEquals("recall", g.step(1600, 0f))
    }

    @Test
    fun noHeadPoseOpensAfterFiveSecondsFromTheFirstFrame() {
        val g = LevelGate()
        g.ask("stage", 0)
        // the Stage's first frame comes ~6 s after the ask on the headset: count from the first frame, not the ask
        assertNull(g.step(6000, null))
        assertNull(g.step(10_999, null))
        assertEquals("stage/nohead", g.step(11_000, null))
    }

    @Test
    fun aValidPoseResetsTheNoHeadClock() {
        val g = LevelGate()
        g.ask("stage", 0)
        assertNull(g.step(0, null))
        assertNull(g.step(4000, -30f))
        assertNull(g.step(4100, null))
        assertNull(g.step(9000, null))
        assertEquals("stage/nohead", g.step(9100, null))
    }

    @Test
    fun nothingAskedNothingOpens() {
        val g = LevelGate()
        assertNull(g.step(0, 0f))
        assertNull(g.step(5000, 0f))
        assertFalse(g.hintWanted(-40f))
        assertEquals(0L, g.waitedMs(5000))
    }

    @Test
    fun cancelStopsAndAskAgainKeepsTheFirstAskTime() {
        val g = LevelGate()
        g.ask("chip", 100)
        g.ask("chip", 900)
        assertEquals(1900L, g.waitedMs(2000))
        g.cancel()
        assertFalse(g.waiting)
        assertNull(g.step(3000, 0f))
        assertNull(g.step(3500, 0f))
    }
}
