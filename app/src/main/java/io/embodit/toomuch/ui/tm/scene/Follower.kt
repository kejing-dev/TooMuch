package io.embodit.toomuch.ui.tm.scene

import io.embodit.toomuch.domain.model.Quat4
import io.embodit.toomuch.domain.model.Vec3
import io.embodit.toomuch.domain.usecase.FacePanelUseCase
import io.embodit.toomuch.platform.TmLog
import io.embodit.toomuch.platform.tracking.HeadPose
import kotlin.math.PI
import kotlin.math.acos

/** A pose in Stage space: where a followed thing sits and its level turn (yaw about the vertical). */
data class FollowPose(val position: Vec3, val yaw: Float) {
    val rotation: Quat4
        get() = Quat4.axisAngle(Vec3.UP, yaw)
}

/**
 * Lazy follow (Spatial Ruler rule): the panel rests where it settled; once its centre has been more
 * than 45 degrees off the view direction, or nearer than [nearM] / farther than [farM], for 0.5 s
 * without a break, it slides to the designed pose of that moment in 0.35 s. It holds still while
 * [hold]. The designed pose is [headOffset] in the level head frame (x right, y up, -z ahead).
 */
internal class Follower(
    private val name: String,
    var headOffset: Vec3,
    private val lean: Quat4 = Quat4.IDENTITY,
    private val nearM: Float = 0.45f,
    private val farM: Float = 1.3f,
) {
    private var started = false
    private var outSinceMs = NEVER
    private var slideStartMs = NEVER
    private var from = FollowPose(Vec3.ZERO, 0f)
    private var to = FollowPose(Vec3.ZERO, 0f)

    var pose: FollowPose = FollowPose(Vec3.ZERO, 0f)
        private set

    val rotation: Quat4
        get() = pose.rotation * lean

    val sliding: Boolean
        get() = slideStartMs != NEVER

    fun reset() {
        started = false
        outSinceMs = NEVER
        slideStartMs = NEVER
    }

    private fun designedFor(head: HeadPose): FollowPose {
        val yaw = FacePanelUseCase.yawOf(head.forward, pose.yaw)
        val level = Quat4.axisAngle(Vec3.UP, yaw)
        return FollowPose(head.position + level.rotate(headOffset), yaw)
    }

    /** Once per frame. Returns true when the pose changed. */
    fun follow(head: HeadPose, nowMs: Long, hold: Boolean): Boolean {
        if (!started) {
            started = true
            pose = designedFor(head)
            TmLog.i("FOLLOW $name park reason=start pos=${TmLog.pos(pose.position)}")
            return true
        }
        if (sliding) {
            slide(nowMs)
            return true
        }
        val toP = pose.position - head.position
        val dist = toP.length
        val away = if (dist < 1e-3f) 0f else Math.toDegrees(acos((toP / dist).dot(head.forward).coerceIn(-1f, 1f)).toDouble()).toFloat()
        val out = away > WINDOW_DEG || dist < nearM || dist > farM
        if (!out || hold) {
            outSinceMs = NEVER
        } else if (outSinceMs == NEVER) {
            outSinceMs = nowMs
        } else if (nowMs - outSinceMs >= OUT_MS) {
            park(head, nowMs)
            return true
        }
        return false
    }

    /** Slides to the designed pose now (reset / debug `park`). */
    fun park(head: HeadPose, nowMs: Long) {
        from = pose
        to = designedFor(head)
        slideStartMs = nowMs
        outSinceMs = NEVER
        slide(nowMs)
    }

    private fun slide(nowMs: Long) {
        val q = ((nowMs - slideStartMs) / SLIDE_MS).coerceIn(0f, 1f)
        val t = Ease.inOutCubic(q)
        var dYaw = to.yaw - from.yaw
        while (dYaw > PI.toFloat()) dYaw -= 2f * PI.toFloat()
        while (dYaw < -PI.toFloat()) dYaw += 2f * PI.toFloat()
        pose = FollowPose(from.position.lerp(to.position, t), from.yaw + dYaw * t)
        if (q >= 1f) slideStartMs = NEVER
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE
        private const val WINDOW_DEG = 55f
        private const val OUT_MS = 500L
        private const val SLIDE_MS = 350f
    }
}
