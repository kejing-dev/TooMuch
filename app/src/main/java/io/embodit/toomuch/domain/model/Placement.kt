package io.embodit.toomuch.domain.model

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/** Level head frame: position and yaw only (pitch and roll ignored, D9). */
data class LevelHead(val position: Vec3, val yaw: Float) {
    val ahead: Vec3
        get() = Vec3(-sin(yaw), 0f, -cos(yaw))
    val right: Vec3
        get() = Vec3(cos(yaw), 0f, -sin(yaw))

    /** Direction at azimuth [azDeg] (+ right) and elevation [elDeg] (+ up). */
    fun direction(azDeg: Float, elDeg: Float): Vec3 {
        val az = Math.toRadians(azDeg.toDouble()).toFloat()
        val el = Math.toRadians(elDeg.toDouble()).toFloat()
        return (ahead * (cos(az) * cos(el)) + right * (sin(az) * cos(el)) + Vec3.UP * sin(el)).normalized()
    }

    /** Azimuth (+ right) and elevation (+ up) of [point], degrees. */
    fun anglesOf(point: Vec3): Pair<Float, Float> {
        val d = point - position
        val f = d.dot(ahead)
        val r = d.dot(right)
        val az = Math.toDegrees(atan2(r, f).toDouble()).toFloat()
        val el = Math.toDegrees(atan2(d.y, hypot(f, r)).toDouble()).toFloat()
        return az to el
    }

    fun at(azDeg: Float, elDeg: Float, range: Float): Vec3 = position + direction(azDeg, elDeg) * range

    companion object {
        fun of(position: Vec3, forward: Vec3): LevelHead {
            val flat = Vec3(forward.x, 0f, forward.z)
            val yaw = if (flat.length < 0.05f) 0f else atan2(-flat.x, -flat.z)
            return LevelHead(position, yaw)
        }
    }
}

/**
 * Where the panels and the tray go (design `panels`, `entities3d.tray`, D8 / D9 / D10).
 */
object Placement {
    /** Body panel: 40 deg left, 13 deg down, 0.8 m; its cone is 31.5 - 48.5 deg left, 3 - 23 deg down. */
    const val BODY_AZ = -40f
    const val BODY_EL = -13f
    const val BODY_RANGE = 0.8f
    const val CONE_AZ_MIN = 31.5f
    const val CONE_AZ_MAX = 48.5f
    const val CONE_DOWN_MIN = 3f
    const val CONE_DOWN_MAX = 23f

    /** Front card: 0.9 m ahead, 8 deg below the line of sight. */
    const val FRONT_RANGE = 0.9f
    const val FRONT_DOWN_DEG = 8f

    /** Tray: 0.9 m ahead, 0.30 m below the head, tilted 10 deg towards the user. */
    const val TRAY_AHEAD = 0.9f
    const val TRAY_BELOW = 0.30f
    const val TRAY_TILT_DEG = 10f

    /** True when azimuth / elevation (degrees, + right / + up) fall into the body panel's cone. */
    fun inBodyCone(az: Float, el: Float): Boolean = -az in CONE_AZ_MIN..CONE_AZ_MAX && -el in CONE_DOWN_MIN..CONE_DOWN_MAX

    /** A circle of angular radius [r] around (az, el) touches the cone (box test grown by r). */
    fun circleTouchesCone(az: Float, el: Float, r: Float): Boolean =
        -az in (CONE_AZ_MIN - r)..(CONE_AZ_MAX + r) && -el in (CONE_DOWN_MIN - r)..(CONE_DOWN_MAX + r)

    fun frontCard(head: LevelHead): Vec3 {
        val drop = FRONT_RANGE * tan(Math.toRadians(FRONT_DOWN_DEG.toDouble())).toFloat()
        return head.position + head.ahead * FRONT_RANGE - Vec3(0f, drop, 0f)
    }

    /** Tray pose: centre of the board's top face, yaw (turning +Z towards the user), tilt. */
    data class TrayPose(val center: Vec3, val yaw: Float) {
        /** Tray-local (x right, y up from the board, z towards the user) -> Stage. */
        fun toStage(x: Float, y: Float, z: Float): Vec3 {
            val tilt = Math.toRadians(TRAY_TILT_DEG.toDouble()).toFloat()
            // tilt about local x: the user's edge goes down, the far edge up
            val ly = y * cos(tilt) - z * sin(tilt)
            val lz = y * sin(tilt) + z * cos(tilt)
            val right = Vec3(cos(yaw), 0f, -sin(yaw))
            val toUser = Vec3(sin(yaw), 0f, cos(yaw))
            return center + right * x + Vec3.UP * ly + toUser * lz
        }

        /** Stage -> tray-local (inverse of [toStage]). */
        fun toLocal(p: Vec3): Vec3 {
            val tilt = Math.toRadians(TRAY_TILT_DEG.toDouble()).toFloat()
            val right = Vec3(cos(yaw), 0f, -sin(yaw))
            val toUser = Vec3(sin(yaw), 0f, cos(yaw))
            val d = p - center
            val x = d.dot(right)
            val ly = d.y
            val lz = d.dot(toUser)
            val y = ly * cos(tilt) + lz * sin(tilt)
            val z = -ly * sin(tilt) + lz * cos(tilt)
            return Vec3(x, y, z)
        }

        /** The board's up normal in Stage space. */
        val normal: Vec3
            get() = (toStage(0f, 1f, 0f) - toStage(0f, 0f, 0f)).normalized()

        /** Where a ray meets the board plane, tray-local, or null. */
        fun rayOnBoard(ray: PointerRay): Vec3? {
            val n = normal
            val denom = ray.direction.dot(n)
            if (kotlin.math.abs(denom) < 1e-4f) return null
            val t = (center - ray.origin).dot(n) / denom
            if (t <= 0f) return null
            return toLocal(ray.at(t))
        }

        /** The eight corners of the board box (2 cm thick, top at y = 0). */
        fun corners(): List<Vec3> {
            val hw = TrayLayoutGeometry.W / 2f
            val hd = TrayLayoutGeometry.D / 2f
            val out = ArrayList<Vec3>()
            for (x in listOf(-hw, hw)) for (z in listOf(-hd, hd)) for (y in listOf(0f, -TrayLayoutGeometry.T)) out += toStage(x, y, z)
            return out
        }
    }

    fun tray(head: LevelHead): TrayPose {
        val c = head.position + head.ahead * TRAY_AHEAD - Vec3(0f, TRAY_BELOW, 0f)
        return TrayPose(c, head.yaw)
    }

    /**
     * The unload card next to a source card (design `panels.unloadCard.placement`). Returns the card
     * centre and whether it was moved to the front fallback (then the source card breathes).
     */
    fun unloadCard(head: LevelHead, source: Vec3, trayBox: Pair<Vec3, Vec3>?): Pair<Vec3, Boolean> {
        val toSource = source - head.position
        // the user's right as seen looking at the source: direction x up
        val sourceRight = Vec3(-toSource.z, 0f, toSource.x).let { if (it.length < 1e-3f) head.right else it.normalized() }
        val candidate = source + sourceRight * 0.25f
        val (cAz, cEl) = head.anglesOf(candidate)
        val near = toSource.length <= 2.0f
        val pitchOk = cEl in -20f..10f
        val clearOfPanels = !inBodyCone(cAz, cEl) && (trayBox == null || !inside(candidate, trayBox))
        val yawOk = kotlin.math.abs(cAz) <= 30f
        if (!yawOk) {
            // opened from far round (e.g. the bag on the left wall via its capsule): 1.0 m ahead, 20 deg towards the source
            val sAz = head.anglesOf(source).first
            val az = if (sAz >= 0f) 20f else -20f
            return head.at(az, -5f, 1.0f) to true
        }
        if (near && pitchOk && clearOfPanels) return candidate to false
        // along the head -> source direction at 1.2 m, pitch clamped
        val (az, el) = head.anglesOf(source)
        val p = head.at(az, el.coerceIn(-20f, 10f), 1.2f)
        if (trayBox != null && inside(p, trayBox)) return Vec3(p.x, trayBox.second.y + 0.1f, p.z) to false
        return p to false
    }

    private fun inside(p: Vec3, box: Pair<Vec3, Vec3>): Boolean =
        p.x in box.first.x..box.second.x && p.y in box.first.y..box.second.y && p.z in box.first.z..box.second.z
}

/** Board dimensions for geometry outside the task (kept in step with `TrayLayout`). */
object TrayLayoutGeometry {
    const val W = 0.80f
    const val D = 0.50f
    const val T = 0.02f
}
