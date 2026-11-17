package io.embodit.toomuch.domain.usecase

import kotlin.math.abs

/**
 * Opens the main window only while the head is level (factory native-window-move §7 item 9, drone-sight v0.1.3): a
 * Planar window opens on the line of sight and perpendicular to it, so opened while looking down it stays tilted (D9),
 * and the SDK cannot turn it upright afterwards. Every open is asked for; the Stage feeds the head pitch each frame; the
 * gate answers "open now" after the head has been within [maxPitchDeg] for [dwellMs], or after [noHeadMs] without a
 * valid head pose (counted from the first such frame, not from the ask). Pure Kotlin, unit-tested (LevelGateTest).
 */
class LevelGate(val maxPitchDeg: Float = 10f, val dwellMs: Long = 300L, val noHeadMs: Long = 5000L) {
    var pending: String? = null
        private set
    private var askedMs = 0L
    private var levelSinceMs = -1L
    private var noHeadSinceMs = -1L

    val waiting: Boolean
        get() = pending != null

    fun ask(why: String, now: Long) {
        if (pending == null) askedMs = now
        pending = why
        levelSinceMs = -1L
        noHeadSinceMs = -1L
    }

    fun cancel() {
        pending = null
        levelSinceMs = -1L
        noHeadSinceMs = -1L
    }

    fun waitedMs(now: Long): Long = if (pending == null) 0L else now - askedMs

    /** The reason to open now, or null to keep waiting. [pitchDeg]: + = looking up; null = no valid head pose. */
    fun step(now: Long, pitchDeg: Float?): String? {
        val why = pending ?: return null
        if (pitchDeg == null || !pitchDeg.isFinite()) {
            levelSinceMs = -1L
            if (noHeadSinceMs < 0L) noHeadSinceMs = now
            if (now - noHeadSinceMs < noHeadMs) return null
            cancel()
            return "$why/nohead"
        }
        noHeadSinceMs = -1L
        if (abs(pitchDeg) > maxPitchDeg) {
            levelSinceMs = -1L
            return null
        }
        if (levelSinceMs < 0L) levelSinceMs = now
        if (now - levelSinceMs < dwellMs) return null
        cancel()
        return why
    }

    /** Show "look straight ahead": an open is waiting and the head is tilted. */
    fun hintWanted(pitchDeg: Float?): Boolean = pending != null && pitchDeg != null && abs(pitchDeg) > maxPitchDeg
}
