package io.embodit.toomuch.domain.engine

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * The head-locked layer (design `overlay`, safety V1 - V3): a warm haze over the whole view and up to
 * six soft blobs that live 35 degrees or more off the view centre.
 *
 * Every visual quantity eases with rate limits, so no frame jumps more than 2 % of its range at
 * 60 fps and nothing changes faster than 0.2 Hz: the haze rises at most 0.003 / s (0 -> 0.18 in
 * >= 60 s), a blob fades in over >= 3 s (one at a time) and out over >= 2 s, blobs drift <= 2 deg / s
 * within +-5 deg of their slot, and at the plateau both only sway downwards (haze 0.14 - 0.18, blobs
 * 0.30 - 0.35, 20 - 40 s periods). Quiet fades everything out in 1 s.
 */
class OverlayModel(seed: Long = 7L) {
    /** A blob slot on the head-locked shell: azimuth (+ right) and elevation (+ up), degrees. */
    data class Slot(val az: Float, val el: Float, val colorIndex: Int)

    class Blob(val slot: Slot, val periodAz: Float, val periodEl: Float, val phaseAz: Float, val phaseEl: Float, val swayPeriod: Float) {
        var alpha = 0f

        // start where the drift would put them at t = 0 (no jump on the first frame)
        var az = slot.az + JITTER_DEG * sin(phaseAz)
        var el = slot.el + JITTER_DEG * sin(phaseEl)
        var active = false
    }

    private val rng = java.util.Random(seed)
    val blobs: List<Blob> =
        SLOTS.map { s ->
            Blob(s, 24f + rng.nextFloat() * 16f, 24f + rng.nextFloat() * 16f, rng.nextFloat() * 6.28f, rng.nextFloat() * 6.28f, 20f + rng.nextFloat() * 20f)
        }

    var haze = 0f
        private set
    var hazeMax = 0f
        private set
    var blobMax = 0f
        private set
    private var time = 0f

    /** Order in which blobs come in: set when the first blob is needed (the lamp side first). */
    private var order: List<Int> = blobs.indices.toList()

    fun resetMax() {
        hazeMax = haze
        blobMax = blobs.maxOf { it.alpha }
    }

    /** Puts the slot nearest to [lampAz], [lampEl] (head frame, degrees) first; call when blobs start. */
    fun orderFrom(lampAz: Float?, lampEl: Float?) {
        if (blobs.any { it.active }) return
        order =
            if (lampAz == null || lampEl == null) blobs.indices.toList()
            else blobs.indices.sortedBy { angularDistance(blobs[it].slot.az, blobs[it].slot.el, lampAz, lampEl) }
    }

    /**
     * One step. [target]: haze and blob targets for this moment; [instant] jumps there (debug only).
     * [dt] real seconds.
     */
    fun step(dt: Float, target: Target, instant: Boolean = false) {
        time += dt
        // haze
        val hazeGoal = if (target.plateau) PLATEAU_HAZE_TOP - PLATEAU_HAZE_SWAY * sway(time, 30f) else target.haze
        haze =
            if (instant) hazeGoal
            else if (target.fast) approach(haze, hazeGoal, HAZE_MAX / FAST_FADE_S * dt, HAZE_MAX / FAST_FADE_S * dt)
            else approach(haze, hazeGoal, HAZE_RISE_PER_S * dt, HAZE_FALL_PER_S * dt)
        haze = haze.coerceIn(0f, HAZE_MAX)
        // blobs: the first [count] of the order are active
        val wanted = target.blobCount.coerceIn(0, blobs.size)
        val activeSet = order.take(wanted).toSet()
        // "fading in" = still below its goal (at the plateau: below the bottom of the sway)
        val settled = (if (target.plateau) PLATEAU_BLOB_TOP - PLATEAU_BLOB_SWAY else target.blobAlpha) - 0.01f
        var fadingIn = if (instant) 0 else blobs.count { it.active && it.alpha < settled }
        for ((i, b) in blobs.withIndex()) {
            // drift first, for every blob, every frame (a skipped frame would make it jump)
            b.az = b.slot.az + JITTER_DEG * sin((2 * PI * time / b.periodAz).toFloat() + b.phaseAz)
            b.el = b.slot.el + JITTER_DEG * sin((2 * PI * time / b.periodEl).toFloat() + b.phaseEl)
            val on = i in activeSet
            if (on && !b.active) {
                if (fadingIn > 0 && !instant) continue
                b.active = true
                fadingIn++
            }
            if (!on) b.active = false
            val goal = if (b.active) goalFor(b, target) else 0f
            b.alpha =
                if (instant) goal
                else if (target.fast) approach(b.alpha, goal, BLOB_MAX / FAST_FADE_S * dt, BLOB_MAX / FAST_FADE_S * dt)
                else approach(b.alpha, goal, (target.blobAlpha.coerceAtLeast(0.05f) / BLOB_FADE_IN_S) * dt, BLOB_MAX / BLOB_FADE_OUT_S * dt)
            b.alpha = b.alpha.coerceIn(0f, BLOB_MAX)
        }
        hazeMax = maxOf(hazeMax, haze)
        blobMax = maxOf(blobMax, blobs.maxOf { it.alpha })
    }

    private fun goalFor(b: Blob, t: Target): Float =
        if (t.plateau) PLATEAU_BLOB_TOP - PLATEAU_BLOB_SWAY * sway(time, b.swayPeriod) else t.blobAlpha

    /** 0..1..0 with period [p] seconds (cosine): only ever subtracted from the top value. */
    private fun sway(t: Float, p: Float): Float = ((1 - cos(2 * PI * t / p)) / 2).toFloat()

    private fun approach(v: Float, goal: Float, up: Float, down: Float): Float =
        if (goal > v) minOf(goal, v + up) else maxOf(goal, v - down)

    /** What the layer should show now. [fast]: the quiet fade (1 s). */
    data class Target(val haze: Float, val blobCount: Int, val blobAlpha: Float, val plateau: Boolean = false, val fast: Boolean = false) {
        companion object {
            val OFF = Target(0f, 0, 0f)
            val QUIET = Target(0f, 0, 0f, fast = true)

            fun forLevel(level: Int, plateau: Boolean = false): Target {
                val l = level.coerceIn(0, 4)
                return Target(HAZE_BY_LEVEL[l], BLOBS_BY_LEVEL[l], BLOB_ALPHA_BY_LEVEL[l], plateau = plateau && l == 4)
            }
        }
    }

    companion object {
        val BLOBS_BY_LEVEL = intArrayOf(0, 0, 2, 4, 6)
        val BLOB_ALPHA_BY_LEVEL = floatArrayOf(0f, 0f, 0.18f, 0.28f, 0.35f)
        val HAZE_BY_LEVEL = floatArrayOf(0f, 0.04f, 0.08f, 0.13f, 0.18f)
        const val HAZE_MAX = 0.18f
        const val BLOB_MAX = 0.35f
        const val HAZE_RISE_PER_S = 0.003f
        const val HAZE_FALL_PER_S = 0.02f
        const val BLOB_FADE_IN_S = 3f
        const val BLOB_FADE_OUT_S = 2f
        const val FAST_FADE_S = 1f
        const val PLATEAU_HAZE_TOP = 0.18f
        const val PLATEAU_HAZE_SWAY = 0.04f
        const val PLATEAU_BLOB_TOP = 0.35f
        const val PLATEAU_BLOB_SWAY = 0.05f
        const val JITTER_DEG = 5f

        /** Blob diameter 12 degrees (design 8 - 14). */
        const val BLOB_RADIUS_DEG = 6f

        /** Radius of the head-locked shell, metres. */
        const val SHELL_RADIUS_M = 2.0f

        /**
         * Six slots: upper left, up, upper right, left (raised to +13), right, lower right; no lower left
         * (that is where the body panel sits). Every centre is ~47 degrees off the view axis, so the
         * edge of a blob with its jitter stays >= 35 degrees out.
         */
        val SLOTS =
            listOf(
                Slot(-38f, 30f, 0),
                Slot(0f, 47f, 1),
                Slot(38f, 30f, 2),
                Slot(-46f, 13f, 0),
                Slot(47f, 0f, 1),
                Slot(38f, -30f, 2),
            )

        /** Angle between two directions given as azimuth / elevation, degrees. */
        fun angularDistance(az1: Float, el1: Float, az2: Float, el2: Float): Float {
            val a1 = Math.toRadians(az1.toDouble())
            val e1 = Math.toRadians(el1.toDouble())
            val a2 = Math.toRadians(az2.toDouble())
            val e2 = Math.toRadians(el2.toDouble())
            val c = sin(e1) * sin(e2) + cos(e1) * cos(e2) * cos(a1 - a2)
            return Math.toDegrees(acos(c.coerceIn(-1.0, 1.0))).toFloat()
        }

        /** Off-axis angle of an azimuth / elevation, degrees. */
        fun offAxis(az: Float, el: Float): Float = angularDistance(az, el, 0f, 0f)
    }
}
