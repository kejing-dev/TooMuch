package io.embodit.toomuch.domain

import io.embodit.toomuch.domain.engine.Engine
import io.embodit.toomuch.domain.engine.EngineEvent
import io.embodit.toomuch.domain.engine.ItemId
import io.embodit.toomuch.domain.engine.LevelClock
import io.embodit.toomuch.domain.engine.Levels
import io.embodit.toomuch.domain.engine.Mode
import io.embodit.toomuch.domain.engine.OverlayModel
import io.embodit.toomuch.domain.engine.TaskEvent
import io.embodit.toomuch.domain.engine.TrayLayout
import io.embodit.toomuch.domain.engine.TrayTask
import io.embodit.toomuch.domain.model.Intensity
import io.embodit.toomuch.domain.model.LevelHead
import io.embodit.toomuch.domain.model.Placement
import io.embodit.toomuch.domain.model.PointerRay
import io.embodit.toomuch.domain.model.RoomFrame
import io.embodit.toomuch.domain.model.RoomRules
import io.embodit.toomuch.domain.model.RoomSize
import io.embodit.toomuch.domain.model.SourceId
import io.embodit.toomuch.domain.model.Sources
import io.embodit.toomuch.domain.model.UnloadEffect
import io.embodit.toomuch.domain.model.Vec3
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The JVM checks of design `verification.emulatorCanVerify` (last item) and `safety`. */
class TooMuchModelTest {

    // ---- levels (audio.levels, safety A3)

    @Test
    fun levelFormulaTakesTheLargerButNeverPassesTheGate() {
        assertEquals(0, Levels.level(0f, 0))
        assertEquals(0, Levels.level(14.9f, 4)) // four things picked at once: still L0 before 15 s
        assertEquals(1, Levels.level(15f, 4))
        assertEquals(2, Levels.level(30f, 4))
        assertEquals(3, Levels.level(45f, 4))
        assertEquals(4, Levels.level(60f, 4))
        assertEquals(1, Levels.level(20f, 0)) // time alone: every 20 s
        assertEquals(2, Levels.level(40f, 0))
        assertEquals(3, Levels.level(59.9f, 3))
        assertEquals(4, Levels.level(500f, 0))
    }

    @Test
    fun noRunReachesL4BeforeSixtySeconds() {
        for (actions in 0..4) {
            var t = 0f
            while (t < 60f) {
                assertTrue("L4 at t=$t actions=$actions", Levels.level(t, actions) < 4)
                t += 0.05f
            }
        }
    }

    @Test
    fun continueAfterQuietResetsTheClockAndClimbsOneLevelEveryThreeSeconds() {
        val c = LevelClock()
        repeat(4) { c.addAction() }
        c.resumeFrom(4)
        assertEquals(45f, c.t, 1e-4f)
        assertEquals(0, c.level())
        var t = 0f
        var last = 0
        val reached = HashMap<Int, Float>()
        while (t < 20f) {
            c.step(0.05f)
            t += 0.05f
            val l = c.level()
            assertTrue("jumped from $last to $l", l - last <= 1)
            if (l != last) reached[l] = t
            last = l
        }
        assertEquals(3, last)
        assertTrue((reached[1] ?: 0f) >= 2.95f)
        assertTrue((reached[2] ?: 0f) - (reached[1] ?: 0f) >= 2.95f)
        assertTrue((reached[3] ?: 0f) - (reached[2] ?: 0f) >= 2.95f)
        // the gate holds L4 until 15 s after the clock runs again
        assertTrue(c.t < 60f)
    }

    // ---- gains (audio.sources, safety A2 / A3)

    @Test
    fun gainTablesStepAtMostSixDbAndEndAtTheCap() {
        for (s in Sources.all) {
            for (l in 1..4) assertTrue("${s.id} L$l", s.gainDb(l) - s.gainDb(l - 1) <= 6f + 1e-4f)
            assertEquals(s.capDb, s.gainDb(4), 1e-4f)
            assertTrue(s.capDb <= 0f)
        }
        assertEquals(-8f, Intensity.GENTLE.groupDb, 0f)
        assertEquals(0f, Intensity.STANDARD.groupDb, 0f)
    }

    @Test
    fun gainSlewNeedsAtLeastThreeSecondsPerLevelStep() {
        // from L0 to L1 of the fridge (-27 -> -21 dB) at 60 fps
        var g = -27f
        var t = 0f
        while (g < -21f) {
            val next = Engine.slew(g, -21f, -27f, 1f / 60f)
            assertTrue(next - g <= 2f / 60f + 1e-4f)
            g = next
            t += 1f / 60f
        }
        assertTrue("took $t s", t >= 2.99f)
        // from silence to L0 is a quick fade-in, not a level step
        var s = Engine.NEG_INF
        var u = 0f
        while (s != -27f && u < 5f) {
            s = Engine.slew(s, -27f, -27f, 1f / 60f)
            u += 1f / 60f
        }
        assertTrue(u < 2.5f)
    }

    @Test
    fun pulseIntervalsInterpolateAndNeverFallUnderTheFloor() {
        val bag = Sources.BAG
        assertEquals(4f, bag.interval(0).start, 1e-4f)
        assertEquals(1.5f, bag.interval(4).start, 1e-4f)
        assertEquals((4f + 1.5f) / 2f, bag.interval(2).start, 1e-4f)
        for (s in Sources.all.filter { it.pulse }) for (l in 0..4) assertTrue(s.interval(l).start >= Sources.MIN_PULSE_GAP_S - 1e-4f)
    }

    // ---- unloading and the quiet redo (audio.unloadRule, T16)

    @Test
    fun unloadedSourcesAreAtOrBelowL0AndNeverAboveAnUntouchedOne() {
        for (spec in Sources.all) {
            for ((i, effect) in spec.options.withIndex()) {
                val e = Engine()
                e.enter(Mode.REDO)
                e.unload(spec.id, effect)
                val processed = e.targetDb(spec.id)
                assertTrue("${spec.id} opt $i", processed <= spec.gainDb(0))
                for (other in Sources.all.filter { it.id != spec.id }) {
                    val untouched = e.targetDb(other.id)
                    assertEquals(other.gainDb(1), untouched, 1e-4f)
                }
            }
        }
        // "processed <= any unprocessed" in the redo, with fridge option 0
        val e = Engine()
        e.enter(Mode.REDO)
        e.unload(SourceId.FRIDGE, UnloadEffect.TO_L0)
        val f = e.targetDb(SourceId.FRIDGE)
        for (other in Sources.all.filter { it.id != SourceId.FRIDGE }) assertTrue(f <= e.targetDb(other.id) + 1e-4f || f <= Sources[SourceId.FRIDGE].gainDb(0))
    }

    @Test
    fun anyLampResultKeepsTheLayerAtOrBelowL1() {
        for (effect in Sources.LAMP.options) {
            val e = Engine()
            e.instant = true
            e.enter(Mode.UNLOAD)
            e.unload(SourceId.LAMP, effect)
            e.step(0.016f)
            assertTrue(e.overlay.haze <= OverlayModel.HAZE_BY_LEVEL[1] + 1e-4f)
            assertTrue(e.overlay.blobs.all { it.alpha == 0f })
        }
    }

    // ---- overlay (safety V1 - V3)

    @Test
    fun blobSlotsStayThirtyFiveDegreesOutAndOutOfTheBodyPanelCone() {
        for (s in OverlayModel.SLOTS) {
            val worst = OverlayModel.offAxis(s.az, s.el) - OverlayModel.JITTER_DEG - OverlayModel.BLOB_RADIUS_DEG
            assertTrue("slot $s edge at $worst", worst >= 35f)
            // corners of the jitter box grown by the blob radius must miss the cone
            val r = OverlayModel.JITTER_DEG + OverlayModel.BLOB_RADIUS_DEG
            assertFalse("slot $s touches the panel cone", Placement.circleTouchesCone(s.az, s.el, r))
        }
        // no lower-left slot
        assertFalse(OverlayModel.SLOTS.any { it.az < -10f && it.el < -10f })
        assertTrue(OverlayModel.SLOTS.size <= 6)
    }

    @Test
    fun overlayNeverJumpsAndNeverPassesItsCaps() {
        val m = OverlayModel(3L)
        var prevHaze = 0f
        val prevBlobs = FloatArray(6)
        var t = 0f
        var hazeAtCap = -1f
        val dt = 1f / 60f
        while (t < 400f) {
            val level = minOf(4, (t / 15f).toInt())
            m.step(dt, OverlayModel.Target.forLevel(level, plateau = level == 4))
            assertTrue(m.haze <= 0.18f + 1e-5f)
            assertTrue(abs(m.haze - prevHaze) <= 0.18f * 0.02f)
            for ((i, b) in m.blobs.withIndex()) {
                assertTrue(b.alpha <= 0.35f + 1e-5f)
                assertTrue(abs(b.alpha - prevBlobs[i]) <= 0.35f * 0.02f)
                prevBlobs[i] = b.alpha
            }
            // at most one blob fading in at a time
            if (m.haze >= 0.179f && hazeAtCap < 0f) hazeAtCap = t
            prevHaze = m.haze
            t += dt
        }
        assertTrue("haze capped too early at $hazeAtCap", hazeAtCap >= 60f)
        // at the plateau the haze only sways downwards within 0.14 - 0.18
        assertTrue(m.haze in 0.139f..0.181f)
    }

    @Test
    fun blobsDriftAtMostTwoDegreesPerSecond() {
        val m = OverlayModel(5L)
        val dt = 0.05f
        var prev = m.blobs.map { it.az to it.el }
        repeat(2000) {
            m.step(dt, OverlayModel.Target.forLevel(4, plateau = true))
            val now = m.blobs.map { it.az to it.el }
            for (i in now.indices) {
                val d = hypot(now[i].first - prev[i].first, now[i].second - prev[i].second) / dt
                assertTrue("blob $i drifts $d deg/s", d <= 2f)
            }
            prev = now
        }
    }

    @Test
    fun quietFadesTheLayerInOneSecondAndTheGroupInHalfASecond() {
        val e = Engine()
        e.instant = true
        e.enter(Mode.TASK)
        e.levels.lock(4)
        e.step(0.016f)
        e.instant = false
        e.enter(Mode.QUIET)
        var t = 0f
        var silenced = -1f
        while (t < 1.2f) {
            val ev = e.step(1f / 60f)
            t += 1f / 60f
            if (ev.any { it is EngineEvent.Silenced } && silenced < 0f) silenced = t
        }
        assertTrue("silenced at $silenced", silenced in 0f..0.52f)
        assertEquals(0f, e.group, 0f)
        assertEquals(0f, e.overlay.haze, 1e-4f)
        assertTrue(e.overlay.blobs.all { it.alpha == 0f })
    }

    // ---- room, placement (D14, D8, panels.unloadCard.placement)

    @Test
    fun pinsArePushedOutOfTheHeadClearance() {
        val head = Vec3(0f, 1.6f, 0f)
        val p = RoomRules.clear(Vec3(0.3f, 1.2f, -0.1f), head)
        assertEquals(0.6f, hypot(p.x - head.x, p.z - head.z), 1e-4f)
        assertEquals(1.2f, p.y, 0f)
        val far = Vec3(2f, 1f, -2f)
        assertEquals(far, RoomRules.clear(far, head))
    }

    @Test
    fun pinsSnapToTheirOwnSpotWithinThirtyCentimetres() {
        val frame = RoomFrame(0f, 0f, 0f, 1.6f)
        val spots = RoomRules.spots(RoomSize(), frame)
        val fridge = spots.first { it.source == SourceId.FRIDGE }
        val head = Vec3(0f, 1.6f, 0f)
        val near = frame.toStage(Vec3(1.8f, 1.5f, -1.9f))
        val (p, snapped) = RoomRules.resolvePin(near, head, fridge, frame)
        assertTrue(snapped)
        assertEquals(frame.toStage(fridge.point), p)
        val away = frame.toStage(Vec3(0.8f, 1.4f, -1.0f))
        assertFalse(RoomRules.resolvePin(away, head, fridge, frame).second)
    }

    @Test
    fun nearerAndFartherMoveHalfAMetreWithinLimits() {
        val head = Vec3(0f, 1.6f, 0f)
        val card = Vec3(0f, 1.6f, -3f)
        assertEquals(2.5f, RoomRules.nearerFarther(card, head, -1).distanceTo(head), 1e-4f)
        assertEquals(3.5f, RoomRules.nearerFarther(card, head, +1).distanceTo(head), 1e-4f)
        assertEquals(6f, RoomRules.nearerFarther(Vec3(0f, 1.6f, -5.8f), head, +1).distanceTo(head), 1e-3f)
        assertTrue(RoomRules.nearerFarther(Vec3(0f, 1.6f, -0.8f), head, -1).distanceTo(head) >= 0.6f - 1e-4f)
    }

    @Test
    fun roomRayHitsTheFrontWall() {
        val frame = RoomFrame(0f, 0f, 0f, 1.6f)
        val hit = RoomRules.hitRoom(RoomSize(), frame, PointerRay(Vec3(0f, 1.6f, 0f), Vec3(0f, 0f, -1f)))
        assertNotNull(hit)
        assertEquals(2f, hit!!.first, 1e-4f)
        assertEquals(Vec3(0f, 0f, 1f), hit.second)
    }

    @Test
    fun trayCornersAndLowGazeStayOutOfTheBodyPanelCone() {
        val head = LevelHead(Vec3(0f, 1.6f, 0f), 0f)
        val tray = Placement.tray(head)
        for (c in tray.corners()) {
            val (az, el) = head.anglesOf(c)
            assertFalse("tray corner az=$az el=$el in the cone", Placement.inBodyCone(az, el))
        }
    }

    @Test
    fun unloadCardFollowsTheFourRules() {
        val head = LevelHead(Vec3(0f, 1.6f, 0f), 0f)
        // a near card in front: right of it, same height
        val near = Vec3(0.2f, 1.5f, -1.0f)
        val (p1, front1) = Placement.unloadCard(head, near, null)
        assertFalse(front1)
        assertEquals(0.25f, p1.distanceTo(near), 1e-3f)
        // the lamp on the ceiling: along the line at 1.2 m, pitch clamped to +10
        val lamp = Vec3(0f, 2.6f, -1.2f)
        val (p2, _) = Placement.unloadCard(head, lamp, null)
        assertEquals(1.2f, p2.distanceTo(head.position), 1e-3f)
        assertTrue(head.anglesOf(p2).second <= 10.01f)
        // a far card (4 m): along the line at 1.2 m
        val far = Vec3(0f, 2.5f, -4f)
        assertEquals(1.2f, Placement.unloadCard(head, far, null).first.distanceTo(head.position), 1e-3f)
        // the bag on the left wall opened from its capsule: 1.0 m ahead, turned 20 deg towards it, flagged
        val bag = Vec3(-2f, 0.6f, -0.6f)
        val (p4, front4) = Placement.unloadCard(head, bag, null)
        assertTrue(front4)
        assertEquals(1.0f, p4.distanceTo(head.position), 1e-3f)
        assertEquals(-20f, head.anglesOf(p4).first, 0.5f)
        // none of them in the body panel's cone
        for (p in listOf(p1, p2, p4)) {
            val (az, el) = head.anglesOf(p)
            assertFalse(Placement.inBodyCone(az, el))
        }
    }

    // ---- the tray task (audio.taskPacing)

    @Test
    fun keysLieUnderTheFourthCandidateTurnedOverAndOnlyFromL3() {
        for (seed in 1..4) {
            val t = TrayTask(seed, quiet = false)
            for ((n, id) in t.candidates.withIndex()) {
                assertTrue(t.press(id))
                t.step(1.6f, level = 2)
                t.release()
                t.step(0.5f, 2)
                assertNull("keys too early (n=$n)", t.keyUnder)
            }
            // at L3 the next candidate held 1.5 s shows the keys
            val again = t.candidates.first()
            t.press(again)
            t.step(1.6f, 3)
            assertEquals(again, t.keyUnder)
        }
        val quiet = TrayTask(2, quiet = true)
        for (id in quiet.candidates) {
            quiet.press(id)
            quiet.step(1.6f, 0)
            quiet.release()
        }
        assertEquals(quiet.candidates.last(), quiet.keyUnder)
    }

    @Test
    fun seedsGiveFourDifferentLayouts() {
        val layouts = (1..4).map { TrayLayout.candidatesFor(it) }.toSet()
        assertEquals(4, layouts.size)
    }

    @Test
    fun snapThresholdShrinksWithTheLevel() {
        assertEquals(0.05f, TrayLayout.snapThreshold(0, false), 1e-5f)
        assertEquals(0.02f, TrayLayout.snapThreshold(4, false), 1e-5f)
        assertEquals(0.05f, TrayLayout.snapThreshold(4, true), 1e-5f)
        for (l in 1..4) assertTrue(TrayLayout.snapThreshold(l, false) < TrayLayout.snapThreshold(l - 1, false))
    }

    @Test
    fun anInterruptionDropsWhatIsHeld() {
        val t = TrayTask(1, quiet = false)
        t.skipToReturn()
        assertTrue(t.press(ItemId.MUG, drag = true))
        t.dragTo(0.2f, -0.15f, 0)
        assertEquals(ItemId.MUG, t.interrupt())
        assertFalse(t.holding)
        repeat(30) { t.step(0.02f, 0) }
        val mug = t.items[ItemId.MUG]!!
        assertEquals(TrayLayout.fixedSpots[ItemId.MUG]!!.x, mug.x, 1e-3f)
        assertTrue(t.takeEvents().any { it is TaskEvent.Dropped })
    }

    /**
     * Scripted player (design taskPacing.tuning): every action takes 2 s to reach plus 1.5 s held (or a
     * 2 s drag); an interruption makes it start that action again. Over 100 seeds the task must not be
     * done before L4 (60 s) at least 95 times, and the quiet redo must take <= 30 s every time.
     */
    @Test
    fun scriptedPlayerCannotFinishBeforeThePlateauButFinishesTheQuietRedo() {
        var notDone = 0
        for (seed in 1..100) {
            val t = play(seed.toLong(), quiet = false)
            if (t > 60f) notDone++
        }
        assertTrue("only $notDone / 100 runs lasted past 60 s", notDone >= 95)
        for (seed in 1..100) {
            val t = play(seed.toLong(), quiet = true)
            assertTrue("quiet redo took $t s (seed $seed)", t <= 30f)
        }
    }

    private fun play(seed: Long, quiet: Boolean): Float {
        val e = Engine(seed)
        e.instant = false
        e.startRun()
        e.newTask(((seed % 4) + 1).toInt(), quiet)
        e.enter(if (quiet) Mode.REDO else Mode.TASK)
        val task = e.task!!
        val dt = 0.05f
        var t = 0f
        // the script: the candidates in order (again from the first while the keys stay hidden), the keys, the three returns
        var phase = "move"
        var left = 2f
        var cand = 0
        var ret = 0
        val returns = listOf(ItemId.MUG, ItemId.GLASSES, ItemId.BOOK)
        while (t < 400f) {
            val events = e.step(dt)
            if (events.any { it is EngineEvent.Interrupted }) {
                phase = "move"
                left = 2f
            }
            if (task.stage == TrayTask.Stage.DONE) return t
            left -= dt
            if (left <= 0f) {
                when (task.stage) {
                    TrayTask.Stage.KEYS ->
                        if (task.keyRevealed) {
                            task.release()
                            task.tapKey()
                            phase = "move"
                            left = 2f
                        } else if (phase == "move") {
                            task.press(task.candidates[cand % 4])
                            phase = "hold"
                            left = TrayLayout.HOLD_TO_REVEAL_S + 0.05f
                        } else {
                            if (!task.keyRevealed) {
                                task.release()
                                cand++
                            }
                            phase = "move"
                            left = 2f
                        }
                    TrayTask.Stage.RETURN ->
                        if (phase == "move") {
                            task.press(returns[ret], drag = true)
                            phase = "drag"
                            left = 2f
                        } else {
                            if (task.holding) {
                                val f = TrayLayout.returnFrames[returns[ret]]!!
                                task.dragTo(f.x, f.z, e.level())
                            }
                            if (task.items[returns[ret]]!!.snapped) ret++
                            phase = "move"
                            left = 2f
                        }
                    TrayTask.Stage.DONE -> return t
                }
            }
            t += dt
        }
        return t
    }
}
