package io.embodit.toomuch.domain.engine

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The level formula of design `audio.levels`: timeLevel = floor(t / 20 s), actionLevel = items lifted
 * or put back (at most 4), gate = floor(t / 15 s); level = min(max(timeLevel, actionLevel), gate), 0..4.
 * Actions can only pull the next level forward to the nearest 15 s step, never skip one: L4 comes at
 * 60 s at the earliest (safety A3).
 */
object Levels {
    const val MAX = 4
    const val TIME_STEP_S = 20f
    const val GATE_STEP_S = 15f
    const val MAX_ACTIONS = 4

    /** Seconds per level while climbing back after "Continue" from the quiet card. */
    const val CLIMB_STEP_S = 3f

    fun level(t: Float, actions: Int): Int {
        val timeLevel = floor(t / TIME_STEP_S).toInt()
        val actionLevel = actions.coerceIn(0, MAX_ACTIONS)
        val gate = floor(t / GATE_STEP_S).toInt()
        return min(max(timeLevel, actionLevel), gate).coerceIn(0, MAX)
    }

    /** levelClock after "Continue" from a level [l]: 15 x max(l - 1, 0) s. */
    fun resumeClock(l: Int): Float = GATE_STEP_S * max(l - 1, 0)

    /** Debug `level n`: the clock that makes the formula give n with enough actions. */
    fun clockFor(n: Int): Float = GATE_STEP_S * n.coerceIn(0, MAX)
}

/**
 * levelClock + the action count + the climb after a quiet pause (design S09 "Continue": gains go up
 * from L0 one level every >= 3 s to L - 1; the gate does not release the next level before that).
 */
class LevelClock {
    /** levelClock, seconds. */
    var t = 0f
        private set
    var actions = 0
        private set

    /** Debug `level n`: fixed until the next `level` or `goto`. */
    var locked: Int? = null
        private set

    private var climbTarget = -1
    private var climbLevel = 0
    private var climbAcc = 0f

    val climbing: Boolean
        get() = climbTarget >= 0

    fun reset() {
        t = 0f
        actions = 0
        locked = null
        climbTarget = -1
        climbLevel = 0
        climbAcc = 0f
    }

    fun addAction() {
        if (actions < Levels.MAX_ACTIONS) actions++
    }

    fun lock(n: Int) {
        locked = n.coerceIn(0, Levels.MAX)
        t = Levels.clockFor(n)
        actions = Levels.MAX_ACTIONS
        climbTarget = -1
    }

    fun unlock() {
        locked = null
    }

    /** "Continue" after quiet from level [l]. */
    fun resumeFrom(l: Int) {
        locked = null
        t = Levels.resumeClock(l)
        val target = (l - 1).coerceAtLeast(0)
        if (target > 0) {
            climbTarget = target
            climbLevel = 0
            climbAcc = 0f
        } else {
            climbTarget = -1
        }
    }

    /** Advances by [dt] scaled seconds; the clock holds while climbing. */
    fun step(dt: Float) {
        if (locked != null) return
        if (climbing) {
            climbAcc += dt
            while (climbAcc >= Levels.CLIMB_STEP_S && climbLevel < climbTarget) {
                climbAcc -= Levels.CLIMB_STEP_S
                climbLevel++
            }
            if (climbLevel >= climbTarget) climbTarget = -1
            return
        }
        t += dt
    }

    fun level(): Int = locked ?: if (climbing) climbLevel else Levels.level(t, actions)
}
