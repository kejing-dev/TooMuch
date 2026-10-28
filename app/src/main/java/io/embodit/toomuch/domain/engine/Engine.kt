package io.embodit.toomuch.domain.engine

import io.embodit.toomuch.domain.model.Intensity
import io.embodit.toomuch.domain.model.SourceId
import io.embodit.toomuch.domain.model.Sources
import io.embodit.toomuch.domain.model.UnloadEffect
import kotlin.math.pow

/** What the sound and light are doing, set by the ViewModel from the design state. */
enum class Mode {
    /** S01 - S03b, S14, S15 from the intro: nothing plays. */
    SILENT,

    /** S04 / S04b / S04c: silent except the 2 s preview of a freshly pinned source. */
    PINNING,

    /** S05: every source at L0, no layer. */
    BASELINE,

    /** S06 - S08: the level clock runs. */
    TASK,

    /** S09: everything to silence in 0.5 s, the layer out in 1 s, the clocks stop. */
    QUIET,

    /** S10 / S10b: unprocessed sources at L2. */
    UNLOAD,

    /** S11: processed sources at their result, the rest at L1, no interruptions. */
    REDO,

    /** S12: everything stops. */
    ENDING,
}

/** One step's noteworthy changes, for the ViewModel and the log. */
sealed interface EngineEvent {
    data class LevelChanged(val level: Int) : EngineEvent

    data class Pulse(val source: SourceId, val variant: Int) : EngineEvent

    data class Interrupted(val source: SourceId, val item: ItemId) : EngineEvent

    data class Task(val event: TaskEvent) : EngineEvent

    /** S08: no user action for 150 s (scaled). */
    data object IdleTimeout : EngineEvent

    /** S10: 60 s in, "Do the rest for me" appears. */
    data object HelpReady : EngineEvent

    /** S09: every gain has reached silence (logged with a ms timestamp, safety A4). */
    data object Silenced : EngineEvent
}

/**
 * The experience clock work, pure Kotlin and stepped once per frame by the scene: level clock, the
 * two other clocks, per-source gains with their slew limits (A3), the group gain (intensity, mute,
 * quiet), pulse schedules, interruptions (T24), the overlay (V1 - V3), ripple levels and the tray task.
 */
class Engine(seed: Long = 1L) {
    private val rng = java.util.Random(seed)

    var mode = Mode.SILENT
        private set
    val levels = LevelClock()
    val overlay = OverlayModel(seed)
    var task: TrayTask? = null
        private set

    /** Scaled seconds: elapsed task time (S06 - S08) and the quiet redo (S11). */
    var elapsedS = 0f
        private set
    var quietS = 0f
        private set
    var unloadS = 0f
        private set
    private var idleS = 0f
    private var helpSent = false

    /** Every app timer runs at this rate (debug `timescale`). */
    var timescale = 1f

    /** Menu open / app in the background: the clocks stop, the sound keeps its level. */
    var paused = false

    var intensity = Intensity.GENTLE
    var muted = false

    /** Debug: skip every ease (screenshots only). */
    var instant = false

    /** Sound back-end in use, for the log (`object` = ObjectAudioComponent, `soundpool` = the QA fallback). */
    val unloaded = LinkedHashMap<SourceId, UnloadEffect>()

    /** A source that was just pinned plays at L1 for this many more seconds (S04b preview). */
    private val preview = HashMap<SourceId, Float>()

    /** Current gain per source, dB (NEG_INF = silent), after the slew limits. */
    val gainDb: MutableMap<SourceId, Float> = SourceId.entries.associateWith { NEG_INF }.toMutableMap()

    /** Extra layers: fridge hiss, clock hard transients (crossfade 0..1), tap bass (crossfade 0..1). */
    var fridgeHissDb = NEG_INF
        private set
    var clockHard = 0f
        private set
    var tapBass = 0f
        private set

    /** Group gain 0..1 (intensity x mute x quiet). */
    var group = 0f
        private set

    private val nextPulse = HashMap<SourceId, Float>()
    private val lastInterrupt = HashMap<SourceId, Float>()
    private var lastAnyInterrupt = -1e9f
    private var clock = 0f
    private var lastLevel = -1
    private var silencedSent = true

    /** Ripple phase in real seconds (period 5 s, never faster). */
    var ripplePhaseS = 0f
        private set

    fun level(): Int = levels.level()

    val plateau: Boolean
        get() = mode == Mode.TASK && level() >= 4

    // -----------------------------------------------------------------------------------------
    // control from the ViewModel
    // -----------------------------------------------------------------------------------------

    fun enter(newMode: Mode) {
        if (newMode == mode) return
        val old = mode
        mode = newMode
        when (newMode) {
            Mode.TASK -> {
                if (old != Mode.QUIET) idleS = 0f
                schedulePulses()
            }
            Mode.QUIET -> silencedSent = false
            Mode.UNLOAD -> {
                if (old != Mode.QUIET) {
                    unloadS = 0f
                    helpSent = false
                }
                schedulePulses()
            }
            Mode.REDO -> {
                if (old != Mode.QUIET) quietS = 0f
                schedulePulses()
            }
            Mode.BASELINE -> schedulePulses()
            else -> Unit
        }
    }

    /** A fresh task run (S05 -> S06 starts the clocks; S11 gets its own quiet task). */
    fun newTask(seed: Int, quiet: Boolean) {
        task = TrayTask(seed, quiet)
    }

    fun clearTask() {
        task = null
    }

    /** S05 "Start": every clock from zero. */
    fun startRun() {
        levels.reset()
        elapsedS = 0f
        quietS = 0f
        idleS = 0f
        unloaded.clear()
        overlay.resetMax()
    }

    /** S09 "Continue" back into the task from level [l]. */
    fun resumeTask(l: Int) {
        levels.resumeFrom(l)
        idleS = 0f
    }

    fun userActed() {
        idleS = 0f
    }

    fun preview(source: SourceId) {
        preview[source] = PREVIEW_S
    }

    fun unload(source: SourceId, effect: UnloadEffect) {
        unloaded[source] = effect
    }

    fun clearUnloads() = unloaded.clear()

    fun setElapsed(taskS: Float, quiet: Float?) {
        elapsedS = taskS
        if (quiet != null) quietS = quiet
    }

    // -----------------------------------------------------------------------------------------
    // per frame
    // -----------------------------------------------------------------------------------------

    /** [dt] real seconds since the last frame. [lampDir]: lamp azimuth / elevation in the head frame. */
    fun step(dt: Float, lampDir: Pair<Float, Float>? = null): List<EngineEvent> {
        val events = ArrayList<EngineEvent>()
        val sdt = dt * timescale
        clock += sdt
        val running = !paused
        if (running) {
            when (mode) {
                Mode.TASK -> {
                    levels.step(sdt)
                    elapsedS += sdt
                    idleS += sdt
                    if (plateau && idleS >= IDLE_TO_UNLOAD_S) {
                        idleS = 0f
                        events += EngineEvent.IdleTimeout
                    }
                }
                Mode.UNLOAD -> {
                    unloadS += sdt
                    if (!helpSent && unloadS >= HELP_AFTER_S) {
                        helpSent = true
                        events += EngineEvent.HelpReady
                    }
                }
                Mode.REDO -> quietS += sdt
                else -> Unit
            }
        }
        val l = level()
        if (l != lastLevel) {
            lastLevel = l
            if (mode == Mode.TASK) events += EngineEvent.LevelChanged(l)
        }
        // the tray
        val t = task
        if (t != null && running && (mode == Mode.TASK || mode == Mode.REDO || mode == Mode.BASELINE)) {
            t.step(sdt, if (mode == Mode.TASK) l else 0)
            for (e in t.takeEvents()) {
                events += EngineEvent.Task(e)
                if (mode == Mode.TASK) {
                    idleS = 0f
                    when (e) {
                        is TaskEvent.Lifted -> if (e.firstTime) levels.addAction()
                        is TaskEvent.Snapped -> levels.addAction()
                        else -> Unit
                    }
                }
            }
        }
        // pulses and interruptions
        if (running) firePulses(sdt, events)
        // gains
        stepGains(dt, sdt)
        stepGroup(dt, events)
        // overlay
        if (lampDir != null) overlay.orderFrom(lampDir.first, lampDir.second)
        overlay.step(dt, overlayTarget(), instant)
        ripplePhaseS += dt
        preview.entries.removeAll { (_, left) -> left <= 0f }
        for (k in preview.keys.toList()) preview[k] = preview[k]!! - dt
        return events
    }

    /** The level each source sits at right now, or null when it is silent by design. */
    fun sourceLevel(id: SourceId): Int? {
        val effect = unloaded[id]
        return when (mode) {
            Mode.SILENT, Mode.ENDING -> null
            Mode.PINNING -> if ((preview[id] ?: 0f) > 0f) 1 else null
            Mode.BASELINE -> 0
            Mode.TASK -> level()
            Mode.QUIET -> null
            Mode.UNLOAD -> if (effect != null) effectLevel(effect) else 2
            Mode.REDO -> if (effect != null) effectLevel(effect) else 1
        }
    }

    private fun effectLevel(e: UnloadEffect): Int? =
        when (e) {
            UnloadEffect.MUTE, UnloadEffect.MUTE_OVERLAY_OFF -> null
            else -> 0
        }

    /** Target gain of a source, dB (NEG_INF = silent). */
    fun targetDb(id: SourceId): Float = sourceLevel(id)?.let { Sources[id].gainDb(it) } ?: NEG_INF

    private fun stepGains(dt: Float, sdt: Float) {
        @Suppress("UNUSED_EXPRESSION") sdt
        for (id in SourceId.entries) {
            val goal = targetDb(id)
            val cur = gainDb[id] ?: NEG_INF
            gainDb[id] =
                when {
                    instant -> goal
                    mode == Mode.QUIET -> {
                        // the group is silencing; underneath, everything drops to L0 for the climb back
                        if (group <= 0f) (if (unloaded[id]?.let { effectLevel(it) == null } == true) NEG_INF else Sources[id].gainDb(0)) else cur
                    }
                    else -> slew(cur, goal, Sources[id].gainDb(0), dt)
                }
        }
        // the cue layers follow the task level of their source
        val fl = sourceLevel(SourceId.FRIDGE)
        val hissGoal = fl?.let { Sources.fridgeHissDb(it) } ?: NEG_INF
        fridgeHissDb = if (instant) hissGoal else slew(fridgeHissDb, hissGoal, -18f, dt)
        val cl = sourceLevel(SourceId.CLOCK) ?: 0
        clockHard = approach(clockHard, if (cl >= Sources.CLOCK_HARD_FROM) 1f else 0f, dt / 3f)
        val tl = sourceLevel(SourceId.TAP) ?: 0
        tapBass = approach(tapBass, if (tl >= Sources.TAP_BASS_FROM) 1f else 0f, dt / 3f)
    }

    private fun stepGroup(dt: Float, events: MutableList<EngineEvent>) {
        val full = 10f.pow(intensity.groupDb / 20f)
        val goal = if (muted || mode == Mode.QUIET) 0f else full
        group =
            when {
                instant -> goal
                mode == Mode.QUIET -> (group - full / QUIET_FADE_S * dt).coerceAtLeast(0f)
                muted -> (group - full / MUTE_FADE_S * dt).coerceAtLeast(0f)
                else -> approach(group, goal, full / GROUP_RISE_S * dt)
            }
        if (mode == Mode.QUIET && group <= 0f && !silencedSent) {
            silencedSent = true
            events += EngineEvent.Silenced
        }
    }

    /** Debug `overlay n`: the layer shows level n whatever the mode, until cleared. */
    var overlayLock: Int? = null

    private fun overlayTarget(): OverlayModel.Target {
        overlayLock?.let { return OverlayModel.Target.forLevel(it, plateau = it >= 4) }
        val lamp = unloaded[SourceId.LAMP]
        fun lampAdjusted(base: OverlayModel.Target): OverlayModel.Target =
            when (lamp) {
                UnloadEffect.MUTE_OVERLAY_OFF -> OverlayModel.Target.OFF
                UnloadEffect.TO_L0_OVERLAY_SOFT -> OverlayModel.Target(LAMP_SOFT_HAZE, 0, 0f)
                // any other source processed: the layer is at most what L1 shows (design unloadRule)
                else -> base
            }
        return when (mode) {
            Mode.TASK -> OverlayModel.Target.forLevel(level(), plateau = plateau)
            Mode.QUIET -> OverlayModel.Target.QUIET
            Mode.UNLOAD -> lampAdjusted(OverlayModel.Target.forLevel(2))
            Mode.REDO -> lampAdjusted(OverlayModel.Target.forLevel(1))
            else -> OverlayModel.Target.OFF
        }
    }

    // -----------------------------------------------------------------------------------------
    // pulses (bag, phone) and the interruptions they cause
    // -----------------------------------------------------------------------------------------

    /** Interval level of a pulse source now: its slow option keeps it at L0. */
    private fun pulseLevel(id: SourceId): Int? {
        val l = sourceLevel(id) ?: return null
        return if (unloaded[id] == UnloadEffect.TO_L0_SLOW_PULSES) 0 else l
    }

    private fun schedulePulses() {
        for (spec in Sources.all.filter { it.pulse }) {
            val l = pulseLevel(spec.id) ?: 0
            nextPulse[spec.id] = clock + draw(spec.interval(l))
        }
    }

    private fun draw(range: ClosedFloatingPointRange<Float>): Float =
        maxOf(Sources.MIN_PULSE_GAP_S, range.start + rng.nextFloat() * (range.endInclusive - range.start))

    private fun firePulses(sdt: Float, events: MutableList<EngineEvent>) {
        @Suppress("UNUSED_EXPRESSION") sdt
        for (spec in Sources.all.filter { it.pulse }) {
            val l = pulseLevel(spec.id) ?: continue
            val due = nextPulse[spec.id] ?: (clock + draw(spec.interval(l))).also { nextPulse[spec.id] = it }
            if (clock < due) continue
            nextPulse[spec.id] = clock + draw(spec.interval(l))
            val variant = rng.nextInt(if (spec.id == SourceId.BAG) 6 else 2)
            events += EngineEvent.Pulse(spec.id, variant)
            interruptBy(spec.id, events)
        }
    }

    /** T24: a shot drops what is held (S06 - S08 only), each source >= 5 s apart, both >= 3 s apart. */
    fun interruptBy(id: SourceId, events: MutableList<EngineEvent>, forced: Boolean = false): Boolean {
        if (mode != Mode.TASK) return false
        val t = task ?: return false
        if (!t.holding) return false
        val last = lastInterrupt[id] ?: -1e9f
        if (!forced && (clock - last < PER_SOURCE_GAP_S || clock - lastAnyInterrupt < ANY_GAP_S)) return false
        val item = t.interrupt() ?: return false
        lastInterrupt[id] = clock
        lastAnyInterrupt = clock
        events += EngineEvent.Interrupted(id, item)
        for (e in t.takeEvents()) events += EngineEvent.Task(e)
        return true
    }

    /** Seconds (scaled) to the next shot of a pulse source, for the `state` line. */
    fun nextPulseIn(id: SourceId): Float? = nextPulse[id]?.let { it - clock }

    fun pulseLevelOf(id: SourceId): Int? = pulseLevel(id)

    companion object {
        const val NEG_INF = Float.NEGATIVE_INFINITY

        /** Below this a gain counts as silence. */
        const val FLOOR_DB = -60f

        /** Rise limit: <= 6 dB per >= 3 s (A3). */
        const val RISE_DB_PER_S = 2f

        /** From silence up to L0: a 2 s fade-in (not a level step). */
        const val FADE_IN_DB_PER_S = 20f
        const val FALL_DB_PER_S = 12f
        const val QUIET_FADE_S = 0.5f
        const val MUTE_FADE_S = 0.2f
        const val GROUP_RISE_S = 1f
        const val PREVIEW_S = 2f
        const val IDLE_TO_UNLOAD_S = 150f
        const val HELP_AFTER_S = 60f
        const val PER_SOURCE_GAP_S = 5f
        const val ANY_GAP_S = 3f
        const val LAMP_SOFT_HAZE = 0.02f
        const val RIPPLE_PERIOD_S = 5f

        fun dbToLinear(db: Float): Float = if (db == NEG_INF || db <= FLOOR_DB) 0f else 10f.pow(db / 20f)

        /** dB slew: up 2 dB/s above [l0Db] (fast below it, from silence), down 12 dB/s. */
        fun slew(cur: Float, goal: Float, l0Db: Float, dt: Float): Float {
            if (cur == goal) return goal
            if (goal == NEG_INF) {
                if (cur == NEG_INF) return NEG_INF
                val next = cur - FALL_DB_PER_S * dt
                return if (next <= FLOOR_DB) NEG_INF else next
            }
            val from = if (cur == NEG_INF) FLOOR_DB else cur
            return if (goal > from) {
                val rate = if (from < l0Db) FADE_IN_DB_PER_S else RISE_DB_PER_S
                // never jump past L0 on the fast part
                val next = from + rate * dt
                if (from < l0Db && next > l0Db) minOf(goal, l0Db) else minOf(goal, next)
            } else {
                maxOf(goal, from - FALL_DB_PER_S * dt)
            }
        }

        private fun approach(v: Float, goal: Float, step: Float): Float = if (goal > v) minOf(goal, v + step) else maxOf(goal, v - step)
    }
}
