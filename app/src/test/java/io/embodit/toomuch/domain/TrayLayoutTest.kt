package io.embodit.toomuch.domain

import io.embodit.toomuch.domain.engine.ItemId
import io.embodit.toomuch.domain.engine.TrayLayout
import io.embodit.toomuch.domain.engine.TrayTask
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * QA #2 q2-2 (D17): the tray's items never overlap and keep >= 5 cm between footprints in every seed; each target
 * is >= 4 cm and >= 2 deg from the eye (tray 0.8 m ahead, 0.55 m under the eyes); revealed keys lie inside their
 * candidate's footprint.
 */
class TrayLayoutTest {
    private data class Rect(val name: String, val x0: Float, val z0: Float, val x1: Float, val z1: Float)

    /** Axis-aligned bounds of the item's footprint at its rest spot (yaw included). */
    private fun footprint(id: ItemId, x: Float, z: Float, yaw: Float): Rect {
        val (w, _, d) = TrayLayout.size[id]!!
        val hx = abs(cos(yaw)) * w / 2f + abs(sin(yaw)) * d / 2f
        val hz = abs(sin(yaw)) * w / 2f + abs(cos(yaw)) * d / 2f
        return Rect(id.tag, x - hx, z - hz, x + hx, z + hz)
    }

    private fun gap(a: Rect, b: Rect): Float {
        val dx = max(0f, max(a.x0 - b.x1, b.x0 - a.x1))
        val dz = max(0f, max(a.z0 - b.z1, b.z0 - a.z1))
        return if (dx == 0f && dz == 0f) -1f else hypot(dx, dz)
    }

    private fun startRects(seed: Int): List<Rect> {
        val t = TrayTask(seed, quiet = false)
        return t.items.values.filter { it.id != ItemId.KEYS }.map { footprint(it.id, it.rest.x, it.rest.z, it.rest.yaw) }
    }

    @Test
    fun itemsKeepFiveCentimetresInEverySeed() {
        for (seed in 1..4) {
            val r = startRects(seed)
            for (i in r.indices) for (j in i + 1 until r.size) {
                val g = gap(r[i], r[j])
                assertTrue("seed $seed ${r[i].name} / ${r[j].name} gap ${"%.3f".format(g)}", g >= TrayLayout.MIN_GAP_M - 1e-4f)
            }
        }
    }

    @Test
    fun itemsStayOnTheBoardAndOffTheOutlinesAndTheHook() {
        val hw = TrayLayout.BOARD_W / 2f
        val hd = TrayLayout.BOARD_D / 2f
        val frames = TrayLayout.returnFrames.map { (id, f) -> footprint(id, f.x, f.z, 0f).copy(name = "frame:${id.tag}") }
        val h = TrayLayout.hook
        val hook = Rect("hook", h.x - 0.006f, h.z - 0.03f, h.x + 0.006f, h.z + 0.01f)
        for (seed in 1..4) {
            for (r in startRects(seed)) {
                assertTrue("seed $seed ${r.name} off the board", r.x0 >= -hw + 0.02f && r.x1 <= hw - 0.02f && r.z0 >= -hd + 0.02f && r.z1 <= hd - 0.02f)
                for (f in frames) assertTrue("seed $seed ${r.name} vs ${f.name} ${gap(r, f)}", gap(r, f) >= TrayLayout.MIN_GAP_M - 1e-4f)
                assertTrue("seed $seed ${r.name} vs hook ${gap(r, hook)}", gap(r, hook) >= TrayLayout.MIN_GAP_M - 1e-4f)
            }
        }
    }

    @Test
    fun targetsAreBigEnoughFromTheEye() {
        // eye 0.8 m behind the tray centre (tray +z is towards the user) and 0.55 m above it; each item at its own
        // rest spot in every seed, the keys at every candidate slot they can be revealed in
        fun distTo(x: Float, z: Float) = hypot(hypot(x, 0.8f - z), 0.55f)
        val spots = ArrayList<Triple<ItemId, Float, Float>>()
        for (seed in 1..4) for (it in TrayTask(seed, quiet = false).items.values) if (it.id != ItemId.KEYS) spots += Triple(it.id, it.rest.x, it.rest.z)
        for (slot in TrayLayout.candidateSlots) spots += Triple(ItemId.KEYS, slot.x, slot.z)
        for ((id, x, z) in spots) {
            val s = TrayLayout.size[id]!!
            val minSide = minOf(s.first, s.third)
            val dist = distTo(x, z)
            val deg = Math.toDegrees(2.0 * atan2(minSide / 2.0, dist.toDouble()))
            assertTrue("${id.tag} ${minSide * 100} cm", minSide >= 0.04f)
            assertTrue("${id.tag} ${"%.2f".format(deg)} deg at ${"%.2f".format(dist)} m", deg >= 2.0)
        }
    }

    @Test
    fun revealedKeysLieInsideTheirCandidateAndItStaysLifted() {
        for (seed in 1..4) {
            val t = TrayTask(seed, quiet = true)
            // turn every candidate over (hold 1.5 s each) until the keys show
            for (c in t.candidates) {
                assertTrue(t.press(c))
                repeat(20) { t.step(0.1f, 0) }
                t.release()
                repeat(10) { t.step(0.1f, 0) }
            }
            val keys = t.items[ItemId.KEYS]!!
            assertTrue("seed $seed keys visible", keys.visible)
            val under = t.candidates.last()
            val c = t.items[under]!!
            val kr = footprint(ItemId.KEYS, keys.x, keys.z, keys.yaw)
            val cr = footprint(under, c.rest.x, c.rest.z, c.rest.yaw)
            assertTrue("seed $seed keys inside ${under.tag}", kr.x0 >= cr.x0 && kr.x1 <= cr.x1 && kr.z0 >= cr.z0 && kr.z1 <= cr.z1)
            assertTrue("seed $seed ${under.tag} lifted off the keys (${c.lift})", c.lift >= TrayLayout.LIFT_M - 1e-3f)
            assertTrue(t.keysWaiting)
        }
    }
}
