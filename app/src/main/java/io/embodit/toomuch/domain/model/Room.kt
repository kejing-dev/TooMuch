package io.embodit.toomuch.domain.model

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A ray in Stage space; [direction] is a unit vector. */
data class PointerRay(val origin: Vec3, val direction: Vec3) {
    fun at(t: Float): Vec3 = origin + direction * t
}

/** Size of the virtual room (design `roomModel.virtualRoom`), metres. */
data class RoomSize(val length: Float = 4.0f, val width: Float = 4.0f, val height: Float = 2.6f, val floorOffset: Float = 0f) {
    fun stepLength(dir: Int) = copy(length = (length + 0.5f * dir).coerceIn(2.5f, 8f))

    fun stepWidth(dir: Int) = copy(width = (width + 0.5f * dir).coerceIn(2.5f, 8f))

    fun stepHeight(dir: Int) = copy(height = ((height + 0.2f * dir) * 10f).let { Math.round(it) / 10f }.coerceIn(2.2f, 3.5f))

    fun stepFloor(dir: Int) = copy(floorOffset = ((floorOffset + 0.1f * dir) * 10f).let { Math.round(it) / 10f }.coerceIn(-0.5f, 0.8f))
}

/**
 * The room's frame: centred on the first head position (level), "ahead" = the first head yaw. Room
 * local: x right, y up from the Stage floor (y = 0), z towards the back (-z ahead).
 */
data class RoomFrame(val centerX: Float, val centerZ: Float, val yaw: Float, val headHeight: Float) {
    /** Unit vectors of the room's right and ahead in Stage space. */
    val right: Vec3
        get() = Vec3(cos(yaw), 0f, -sin(yaw))
    val ahead: Vec3
        get() = Vec3(-sin(yaw), 0f, -cos(yaw))

    fun toStage(local: Vec3): Vec3 = Vec3(centerX, 0f, centerZ) + right * local.x + Vec3.UP * local.y + ahead * (-local.z)

    fun toLocal(stage: Vec3): Vec3 {
        val d = stage - Vec3(centerX, 0f, centerZ)
        return Vec3(d.dot(right), stage.y, -d.dot(ahead))
    }

    fun dirToStage(local: Vec3): Vec3 = right * local.x + Vec3.UP * local.y + ahead * (-local.z)

    /** Stage yaw (about +Y, turning +Z) of a room-local yaw. */
    fun yawToStage(localYaw: Float): Float = yaw + localYaw

    companion object {
        /** From a head pose: its level position and heading ([forward] = the view direction). */
        fun from(head: Vec3, forward: Vec3): RoomFrame {
            val flat = Vec3(forward.x, 0f, forward.z)
            val yaw = if (flat.length < 0.05f) 0f else atan2(-flat.x, -flat.z)
            return RoomFrame(head.x, head.z, yaw, head.y)
        }
    }
}

/** Shape of a default-spot outline. */
enum class FrameShape { RECT, CIRCLE }

/**
 * A default spot (design `roomModel.defaultSpots`): where the source sits, the outline drawn around
 * it, and the surface normal pointing into the room (the card faces along it when snapped).
 */
data class DefaultSpot(
    val source: SourceId,
    /** Room-local position of the source point. */
    val point: Vec3,
    /** Room-local centre of the outline (the fridge outline stands on the floor). */
    val frameCenter: Vec3,
    val frameW: Float,
    val frameH: Float,
    val shape: FrameShape,
    /** Room-local inward normal of the surface. */
    val normal: Vec3,
)

object RoomRules {
    const val SNAP_M = 0.3f

    /** Nothing placed comes closer than this to the head, horizontally (D14). */
    const val CLEAR_M = 0.6f
    const val NEAR_FAR_STEP_M = 0.5f
    const val MIN_DIST_M = 0.6f
    const val MAX_DIST_M = 6f
    const val AIM_MIN_M = 0.5f
    const val AIM_MAX_M = 6f
    const val PUSH_PULL_M_S = 0.8f

    /** Thumbstick: dead zone 0.2, full deflection 90 deg/s (life-size-creatures). */
    const val STICK_DEAD = 0.2f
    const val STICK_RATE_DEG_S = 90f

    fun spots(size: RoomSize, frame: RoomFrame): List<DefaultSpot> {
        val hw = size.width / 2f
        val hl = size.length / 2f
        val floor = size.floorOffset
        val ceil = floor + size.height
        val tray = frame.headHeight - 0.30f
        return listOf(
            DefaultSpot(SourceId.FRIDGE, Vec3(hw - 0.45f, floor + 1.5f, -hl), Vec3(hw - 0.45f, floor + 0.85f, -hl), 0.6f, 1.7f, FrameShape.RECT, Vec3(0f, 0f, 1f)),
            DefaultSpot(SourceId.CLOCK, Vec3(0f, floor + 1.9f, -hl), Vec3(0f, floor + 1.9f, -hl), 0.3f, 0.3f, FrameShape.CIRCLE, Vec3(0f, 0f, 1f)),
            DefaultSpot(SourceId.BAG, Vec3(-hw, floor + 0.6f, -0.6f), Vec3(-hw, floor + 0.6f, -0.6f), 0.4f, 0.5f, FrameShape.RECT, Vec3(1f, 0f, 0f)),
            DefaultSpot(SourceId.TAP, Vec3(hw, floor + 1.0f, 0f), Vec3(hw, floor + 1.0f, 0f), 0.3f, 0.3f, FrameShape.RECT, Vec3(-1f, 0f, 0f)),
            DefaultSpot(SourceId.LAMP, Vec3(0f, ceil, -1.2f), Vec3(0f, ceil, -1.2f), 1.2f, 0.15f, FrameShape.RECT, Vec3(0f, -1f, 0f)),
            DefaultSpot(SourceId.PHONE, Vec3(0.75f, tray, -0.9f), Vec3(0.75f, tray, -0.9f), 0.08f, 0.16f, FrameShape.RECT, Vec3(0f, 1f, 0f)),
        )
    }

    /**
     * Where a ray from inside meets the room box (room-local), or null when it starts outside / misses.
     * Returns the distance and the inward normal of the face hit.
     */
    fun hitRoom(size: RoomSize, frame: RoomFrame, ray: PointerRay): Pair<Float, Vec3>? {
        val o = frame.toLocal(ray.origin)
        val d = Vec3(ray.direction.dot(frame.right), ray.direction.y, -ray.direction.dot(frame.ahead))
        val hw = size.width / 2f
        val hl = size.length / 2f
        val y0 = size.floorOffset
        val y1 = size.floorOffset + size.height
        var best = Float.MAX_VALUE
        var normal = Vec3.UP
        fun test(t: Float, n: Vec3, inside: (Vec3) -> Boolean) {
            if (t > 1e-3f && t < best && inside(o + d * t)) {
                best = t
                normal = n
            }
        }
        if (abs(d.x) > 1e-6f) {
            test((hw - o.x) / d.x, Vec3(-1f, 0f, 0f)) { p -> p.y in y0..y1 && abs(p.z) <= hl }
            test((-hw - o.x) / d.x, Vec3(1f, 0f, 0f)) { p -> p.y in y0..y1 && abs(p.z) <= hl }
        }
        if (abs(d.z) > 1e-6f) {
            test((hl - o.z) / d.z, Vec3(0f, 0f, -1f)) { p -> p.y in y0..y1 && abs(p.x) <= hw }
            test((-hl - o.z) / d.z, Vec3(0f, 0f, 1f)) { p -> p.y in y0..y1 && abs(p.x) <= hw }
        }
        if (abs(d.y) > 1e-6f) {
            test((y1 - o.y) / d.y, Vec3(0f, -1f, 0f)) { p -> abs(p.x) <= hw && abs(p.z) <= hl }
            test((y0 - o.y) / d.y, Vec3(0f, 1f, 0f)) { p -> abs(p.x) <= hw && abs(p.z) <= hl }
        }
        return if (best == Float.MAX_VALUE) null else best to normal
    }

    /**
     * A source put down at [point] (Stage) for [source]: pushed out of the head's clearance along the
     * line from the head, then snapped to its own default spot when within 0.3 m. Returns the final
     * Stage point and whether it snapped.
     */
    fun resolvePin(point: Vec3, head: Vec3, spot: DefaultSpot?, frame: RoomFrame): Pair<Vec3, Boolean> {
        var p = clear(point, head)
        if (spot != null) {
            val s = frame.toStage(spot.point)
            if (s.distanceTo(p) <= SNAP_M) return s to true
        }
        return p to false
    }

    /** Pushes [point] out to [CLEAR_M] horizontally from [head] along the head -> point line (D14). */
    fun clear(point: Vec3, head: Vec3): Vec3 {
        val dx = point.x - head.x
        val dz = point.z - head.z
        val h = hypot(dx, dz)
        if (h >= CLEAR_M) return point
        if (h < 1e-3f) return Vec3(head.x, point.y, head.z - CLEAR_M)
        val k = CLEAR_M / h
        return Vec3(head.x + dx * k, point.y, head.z + dz * k)
    }

    /** "Nearer / Farther": along the head -> card line by 0.5 m, clamped to 0.6 - 6 m, then cleared. */
    fun nearerFarther(card: Vec3, head: Vec3, dir: Int): Vec3 {
        val to = card - head
        val d = to.length
        if (d < 1e-3f) return card
        val nd = (d + NEAR_FAR_STEP_M * dir).coerceIn(MIN_DIST_M, MAX_DIST_M)
        return clear(head + to / d * nd, head)
    }

    /** Thumbstick yaw rate, radians per second, for a stick x in -1..1 (0 inside the dead zone). */
    fun stickYawRate(x: Float): Float {
        val a = abs(x)
        if (a < STICK_DEAD) return 0f
        val k = (a - STICK_DEAD) / (1f - STICK_DEAD)
        return -Math.signum(x) * k * Math.toRadians(STICK_RATE_DEG_S.toDouble()).toFloat()
    }
}
