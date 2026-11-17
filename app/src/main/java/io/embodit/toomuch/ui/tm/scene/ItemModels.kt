package io.embodit.toomuch.ui.tm.scene

import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.core.ecs.resource.MeshModel
import io.embodit.toomuch.domain.engine.ItemId
import io.embodit.toomuch.ui.theme.TmColors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Recognisable tray things built in code (QA #2 q2-1, B12: they were scaled unit cubes): a mug with a handle and
 * coffee, keys with bits on a ring, folded glasses with lenses, a book with its page block, a spiral notebook,
 * envelopes with a flap and a stamp, a folded striped scarf with fringes, a remote with buttons.
 *
 * The scene is unlit (design colours exact), so every part is split into two meshes by face direction: faces looking
 * up get the colour, the others a darker tone ([ItemTone]); that reads as light from above without a light.
 * Item origin = centre of the footprint on the board, +x right, +y up, +z towards the user (sizes: TrayLayout.size).
 */
internal object ItemModels {
    /** One coloured part of an item; [build] fills the builder in item-local metres. */
    class Part(val color: Color, val build: (MeshBuilder) -> Unit)

    val PAPER = Color(0xFFEFE8D8)
    val PAPER_EDGE = Color(0xFFD9CFB8)
    val INK = Color(0xFF8A8F99)
    val STAMP_RED = Color(0xFFC0473D)
    val STAMP_BLUE = Color(0xFF3F6FA8)
    val METAL = Color(0xFFB9BEC4)
    val LENS = Color(0xFFA9C7D8)
    val COFFEE = Color(0xFF4A2E1E)
    val BUTTON = Color(0xFF9DA3AB)
    val RED = Color(0xFFC8433A)
    val STRIPE = Color(0xFFE2C9A0)
    val LABEL = Color(0xFFE8E2D2)
    val ELASTIC = Color(0xFF1E2A24)

    fun parts(id: ItemId): List<Part> =
        when (id) {
            ItemId.MUG -> mug()
            ItemId.KEYS -> keys()
            ItemId.GLASSES -> glasses()
            ItemId.BOOK -> book()
            ItemId.NOTEBOOK -> notebook()
            ItemId.ENVELOPE1 -> envelope(STAMP_RED)
            ItemId.ENVELOPE2 -> envelope(STAMP_BLUE)
            ItemId.SCARF -> scarf()
            ItemId.REMOTE -> remote()
        }

    private fun mug(): List<Part> =
        listOf(
            Part(TmColors.MugBlue) { b ->
                b.tube(0f, 0f, 0f, 0.040f, 0.090f, outward = true)
                b.tube(0f, 0.012f, 0f, 0.0345f, 0.078f, outward = false) // inner wall
                b.annulus(0f, 0.090f, 0f, 0.0345f, 0.040f, up = true) // rim
                b.disc(0f, 0f, 0f, 0.040f, up = false)
                // handle: half a torus on the right, in the x-y plane
                b.torus(0.040f, 0.048f, 0f, 0.021f, 0.0055f, axis = 'z', from = -PI / 2, to = PI / 2)
            },
            Part(COFFEE) { b -> b.disc(0f, 0.072f, 0f, 0.0345f, up = true) },
        )

    private fun keys(): List<Part> =
        listOf(
            Part(METAL) { b -> b.torus(-0.030f, 0.004f, 0f, 0.015f, 0.0028f, axis = 'y') },
            Part(TmColors.Brass) { b ->
                key(b, -0.016f, 0f, -0.28f)
                key(b, -0.018f, 0.004f, 0.38f)
            },
        )

    /** One key lying flat: round bow at the ring, a blade with three bits, rotated [yaw] around the ring side. */
    private fun key(b: MeshBuilder, x0: Float, y0: Float, yaw: Float) {
        b.withYaw(x0, 0f, yaw) {
            b.cylinder(0.012f, y0, 0f, 0.011f, 0.003f, 18)
            b.box(0.047f, y0 + 0.0015f, 0f, 0.048f, 0.003f, 0.008f)
            for (k in 0 until 3) b.box(0.035f + 0.011f * k, y0 + 0.0015f, 0.0055f, 0.005f, 0.003f, 0.004f + 0.0015f * (k % 2))
        }
    }

    private fun glasses(): List<Part> =
        listOf(
            Part(TmColors.Glasses) { b ->
                for (s in listOf(-1f, 1f)) b.ellipseTorus(s * 0.034f, 0.006f, 0.003f, 0.027f, 0.019f, 0.0028f)
                b.torus(0f, 0.008f, -0.006f, 0.008f, 0.0022f, axis = 'z', from = 0.0, to = PI) // bridge
                // temples folded behind the rims
                b.box(0f, 0.016f, -0.016f, 0.13f, 0.003f, 0.004f)
                b.box(0f, 0.021f, -0.012f, 0.13f, 0.003f, 0.004f)
            },
            Part(LENS) { b -> for (s in listOf(-1f, 1f)) b.ellipseDisc(s * 0.034f, 0.0055f, 0.003f, 0.026f, 0.018f) },
        )

    private fun book(): List<Part> =
        listOf(
            Part(TmColors.Book) { b ->
                b.box(0f, 0.002f, 0f, 0.160f, 0.004f, 0.110f) // back cover
                b.box(0f, 0.028f, 0f, 0.160f, 0.004f, 0.110f) // front cover
                b.box(-0.078f, 0.015f, 0f, 0.004f, 0.030f, 0.110f) // spine
            },
            Part(PAPER) { b -> b.box(0.003f, 0.015f, 0f, 0.150f, 0.022f, 0.104f) },
            Part(LABEL) { b -> b.box(0.006f, 0.0303f, 0f, 0.100f, 0.0006f, 0.040f) },
        )

    private fun notebook(): List<Part> =
        listOf(
            Part(TmColors.Notebook) { b ->
                b.box(0.004f, 0.0015f, 0f, 0.162f, 0.003f, 0.120f)
                b.box(0.004f, 0.0145f, 0f, 0.162f, 0.003f, 0.120f)
            },
            Part(PAPER) { b -> b.box(0.005f, 0.008f, 0f, 0.156f, 0.010f, 0.114f) },
            Part(METAL) { b ->
                // the spiral: rings around the left edge
                for (k in 0 until 10) b.torus(-0.077f, 0.008f, -0.049f + 0.0109f * k, 0.0085f, 0.0012f, axis = 'z', segments = 14, tube = 6)
            },
            Part(ELASTIC) { b -> b.box(0.062f, 0.008f, 0f, 0.006f, 0.0175f, 0.1215f) },
        )

    private fun envelope(stamp: Color): List<Part> =
        listOf(
            Part(PAPER) { b -> b.box(0f, 0.0025f, 0f, 0.190f, 0.005f, 0.100f) },
            // the flap: a triangle from the back edge to the middle
            Part(PAPER_EDGE) { b -> b.triangleSlab(-0.093f, -0.048f, 0.093f, -0.048f, 0f, 0.012f, 0.0051f, 0.0008f) },
            Part(stamp) { b -> b.box(0.072f, 0.0054f, 0.028f, 0.026f, 0.0008f, 0.030f) },
            Part(INK) { b -> for (k in 0 until 3) b.box(-0.005f + 0.006f * k, 0.0054f, 0.022f + 0.010f * k, 0.070f - 0.012f * k, 0.0006f, 0.003f) },
        )

    private fun scarf(): List<Part> =
        listOf(
            Part(TmColors.Scarf) { b ->
                // three folds of knit, each a little smaller and shifted
                b.box(0f, 0.0055f, 0f, 0.180f, 0.011f, 0.120f)
                b.box(0.004f, 0.0165f, -0.002f, 0.172f, 0.011f, 0.114f)
                b.box(-0.003f, 0.0275f, 0.002f, 0.164f, 0.011f, 0.108f)
            },
            Part(STRIPE) { b ->
                for (k in 0 until 4) b.box(-0.060f + 0.040f * k, 0.0333f, 0.002f, 0.012f, 0.0006f, 0.108f)
                for (s in listOf(-1f, 1f)) b.box(s * 0.0875f, 0.0055f, 0f, 0.005f, 0.0112f, 0.1205f)
            },
            Part(TmColors.Scarf) { b ->
                // fringes on both short ends
                for (s in listOf(-1f, 1f)) for (k in 0 until 8) b.box(s * 0.095f, 0.002f, -0.049f + 0.014f * k, 0.012f, 0.003f, 0.004f)
            },
        )

    private fun remote(): List<Part> =
        listOf(
            Part(TmColors.Remote) { b -> b.box(0f, 0.008f, 0f, 0.150f, 0.016f, 0.045f) },
            Part(BUTTON) { b ->
                b.annulus(-0.022f, 0.0162f, 0f, 0.006f, 0.013f, up = true) // direction pad
                b.cylinder(-0.022f, 0.016f, 0f, 0.0045f, 0.0025f, 16)
                for (r in 0 until 3) for (c in 0 until 3) b.cylinder(0.014f + 0.013f * c, 0.016f, -0.013f + 0.013f * r, 0.0038f, 0.002f, 12)
            },
            Part(RED) { b -> b.cylinder(-0.060f, 0.016f, -0.010f, 0.005f, 0.0025f, 16) },
        )
}

/** Darker tone for faces that do not look up (no lights in the scene). */
internal object ItemTone {
    fun side(c: Color): Color = Color(red = c.red * 0.72f, green = c.green * 0.72f, blue = c.blue * 0.70f, alpha = 1f)
}

/**
 * Collects triangles; [build] returns two meshes: faces whose normal points up (y > 0.55) and the rest.
 * Primitives take item-local metres; [withYaw] turns what is added inside around a vertical axis.
 */
internal class MeshBuilder {
    private val up = Tris()
    private val side = Tris()
    private var ox = 0f
    private var oz = 0f
    private var cosY = 1f
    private var sinY = 0f

    private class Tris {
        val pos = ArrayList<Vector3>()
        val nrm = ArrayList<Vector3>()
        val idx = ArrayList<Int>()
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE

        fun add(a: FloatArray, b: FloatArray, c: FloatArray, n: FloatArray) {
            val base = pos.size
            for (p in listOf(a, b, c)) {
                pos += Vector3(p[0], p[1], p[2])
                nrm += Vector3(n[0], n[1], n[2])
                minX = minOf(minX, p[0]); minY = minOf(minY, p[1]); minZ = minOf(minZ, p[2])
                maxX = maxOf(maxX, p[0]); maxY = maxOf(maxY, p[1]); maxZ = maxOf(maxZ, p[2])
            }
            idx += base
            idx += base + 1
            idx += base + 2
        }
    }

    fun withYaw(px: Float, pz: Float, yaw: Float, block: () -> Unit) {
        val saved = floatArrayOf(ox, oz, cosY, sinY)
        ox = px
        oz = pz
        cosY = cos(yaw)
        sinY = sin(yaw)
        block()
        ox = saved[0]; oz = saved[1]; cosY = saved[2]; sinY = saved[3]
    }

    private fun tx(x: Float, y: Float, z: Float): FloatArray = floatArrayOf(ox + x * cosY - z * sinY, y, oz + x * sinY + z * cosY)

    /** One triangle (item-local, before the yaw); front face counter-clockwise seen from outside. */
    fun tri(a: FloatArray, b: FloatArray, c: FloatArray) {
        val pa = tx(a[0], a[1], a[2])
        val pb = tx(b[0], b[1], b[2])
        val pc = tx(c[0], c[1], c[2])
        val ux = pb[0] - pa[0]; val uy = pb[1] - pa[1]; val uz = pb[2] - pa[2]
        val vx = pc[0] - pa[0]; val vy = pc[1] - pa[1]; val vz = pc[2] - pa[2]
        var nx = uy * vz - uz * vy
        var ny = uz * vx - ux * vz
        var nz = ux * vy - uy * vx
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        if (l < 1e-12f) return
        nx /= l; ny /= l; nz /= l
        (if (ny > 0.55f) up else side).add(pa, pb, pc, floatArrayOf(nx, ny, nz))
    }

    fun quad(a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray) {
        tri(a, b, c)
        tri(a, c, d)
    }

    private fun v(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)

    /** Axis-aligned box centred at (cx, cy, cz). */
    // gate:allow box-helper flat parts inside the modelled tray things (book covers, key bits, remote body), never a whole item
    fun box(cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float) {
        val x0 = cx - sx / 2; val x1 = cx + sx / 2
        val y0 = cy - sy / 2; val y1 = cy + sy / 2
        val z0 = cz - sz / 2; val z1 = cz + sz / 2
        quad(v(x0, y1, z0), v(x0, y1, z1), v(x1, y1, z1), v(x1, y1, z0)) // top
        quad(v(x0, y0, z0), v(x1, y0, z0), v(x1, y0, z1), v(x0, y0, z1)) // bottom
        quad(v(x0, y0, z1), v(x1, y0, z1), v(x1, y1, z1), v(x0, y1, z1)) // +z
        quad(v(x1, y0, z0), v(x0, y0, z0), v(x0, y1, z0), v(x1, y1, z0)) // -z
        quad(v(x1, y0, z1), v(x1, y0, z0), v(x1, y1, z0), v(x1, y1, z1)) // +x
        quad(v(x0, y0, z0), v(x0, y0, z1), v(x0, y1, z1), v(x0, y1, z0)) // -x
    }

    /** Vertical cylinder standing on y = [y] with height [h]. */
    fun cylinder(cx: Float, y: Float, cz: Float, r: Float, h: Float, segments: Int = 20) {
        tube(cx, y, cz, r, h, outward = true, segments = segments)
        disc(cx, y + h, cz, r, up = true, segments = segments)
    }

    /** Side wall of a vertical cylinder; [outward] false = the inside of a cup. */
    fun tube(cx: Float, y: Float, cz: Float, r: Float, h: Float, outward: Boolean, segments: Int = 28) {
        for (i in 0 until segments) {
            val a0 = 2 * PI * i / segments
            val a1 = 2 * PI * (i + 1) / segments
            val p0 = v(cx + (cos(a0) * r).toFloat(), y, cz + (sin(a0) * r).toFloat())
            val p1 = v(cx + (cos(a1) * r).toFloat(), y, cz + (sin(a1) * r).toFloat())
            val p2 = v(p1[0], y + h, p1[2])
            val p3 = v(p0[0], y + h, p0[2])
            if (outward) quad(p0, p3, p2, p1) else quad(p0, p1, p2, p3)
        }
    }

    fun disc(cx: Float, y: Float, cz: Float, r: Float, up: Boolean, segments: Int = 28) = annulus(cx, y, cz, 0f, r, up, segments)

    fun annulus(cx: Float, y: Float, cz: Float, r0: Float, r1: Float, up: Boolean, segments: Int = 28) {
        for (i in 0 until segments) {
            val a0 = 2 * PI * i / segments
            val a1 = 2 * PI * (i + 1) / segments
            val o0 = v(cx + (cos(a0) * r1).toFloat(), y, cz + (sin(a0) * r1).toFloat())
            val o1 = v(cx + (cos(a1) * r1).toFloat(), y, cz + (sin(a1) * r1).toFloat())
            val i0 = v(cx + (cos(a0) * r0).toFloat(), y, cz + (sin(a0) * r0).toFloat())
            val i1 = v(cx + (cos(a1) * r0).toFloat(), y, cz + (sin(a1) * r0).toFloat())
            if (up) quad(i0, i1, o1, o0) else quad(i0, o0, o1, i1)
        }
    }

    /** Flat ellipse (lens) facing up. */
    fun ellipseDisc(cx: Float, y: Float, cz: Float, rx: Float, rz: Float, segments: Int = 28) {
        val c = v(cx, y, cz)
        for (i in 0 until segments) {
            val a0 = 2 * PI * i / segments
            val a1 = 2 * PI * (i + 1) / segments
            tri(c, v(cx + (cos(a1) * rx).toFloat(), y, cz + (sin(a1) * rz).toFloat()), v(cx + (cos(a0) * rx).toFloat(), y, cz + (sin(a0) * rz).toFloat()))
        }
    }

    /**
     * Torus around [axis] ('y' = lying flat, 'z' = standing in the x-y plane) through (cx, cy, cz), ring radius [rr],
     * tube radius [tr], only the arc [from]..[to] (radians).
     */
    fun torus(cx: Float, cy: Float, cz: Float, rr: Float, tr: Float, axis: Char, from: Double = 0.0, to: Double = 2 * PI, segments: Int = 24, tube: Int = 8) =
        sweep(segments, tube, from, to) { a, b ->
            val ring = rr + tr * cos(b).toFloat()
            val off = tr * sin(b).toFloat()
            when (axis) {
                'y' -> v(cx + ring * cos(a).toFloat(), cy + off, cz + ring * sin(a).toFloat())
                else -> v(cx + ring * cos(a).toFloat(), cy + ring * sin(a).toFloat(), cz + off)
            }
        }

    /** A flat elliptical ring (glasses rim) lying on the tray. */
    fun ellipseTorus(cx: Float, cy: Float, cz: Float, rx: Float, rz: Float, tr: Float, segments: Int = 28, tube: Int = 6) =
        sweep(segments, tube, 0.0, 2 * PI) { a, b ->
            val k = 1f + tr * cos(b).toFloat() / ((rx + rz) / 2f)
            v(cx + rx * k * cos(a).toFloat(), cy + tr * sin(b).toFloat(), cz + rz * k * sin(a).toFloat())
        }

    private fun sweep(segments: Int, tube: Int, from: Double, to: Double, at: (Double, Double) -> FloatArray) {
        for (i in 0 until segments) {
            val a0 = from + (to - from) * i / segments
            val a1 = from + (to - from) * (i + 1) / segments
            for (j in 0 until tube) {
                val b0 = 2 * PI * j / tube
                val b1 = 2 * PI * (j + 1) / tube
                val p00 = at(a0, b0); val p10 = at(a1, b0); val p11 = at(a1, b1); val p01 = at(a0, b1)
                // winding chosen so the outer surface's normal points out (it decides the up / side split)
                quad(p00, p01, p11, p10)
            }
        }
    }

    /** A thin raised triangle (envelope flap) with corners a, b on the back edge and its tip at (tx, tz). */
    fun triangleSlab(ax: Float, az: Float, bx: Float, bz: Float, tx: Float, tz: Float, y: Float, h: Float) {
        val a0 = v(ax, y, az); val b0 = v(bx, y, bz); val t0 = v(tx, y, tz)
        val a1 = v(ax, y + h, az); val b1 = v(bx, y + h, bz); val t1 = v(tx, y + h, tz)
        tri(a1, t1, b1)
        quad(a0, b0, b1, a1)
        quad(b0, t0, t1, b1)
        quad(t0, a0, a1, t1)
    }

    /** The two meshes (faces up, faces sideways / down); null when a group is empty. */
    fun build(name: String): Pair<MeshResource?, MeshResource?> = mesh(up, "$name:up") to mesh(side, "$name:side")

    private fun mesh(t: Tris, name: String): MeshResource? {
        if (t.idx.isEmpty()) return null
        val c = Vector3((t.minX + t.maxX) / 2f, (t.minY + t.maxY) / 2f, (t.minZ + t.maxZ) / 2f)
        val h = Vector3(maxOf(abs(t.maxX - t.minX) / 2f, 0.001f), maxOf(abs(t.maxY - t.minY) / 2f, 0.001f), maxOf(abs(t.maxZ - t.minZ) / 2f, 0.001f))
        return MeshResource.createWithMeshModel(model = MeshModel(positions = t.pos, triangleIndices = t.idx, normals = t.nrm), bounds = BoundingBox(center = c, halfExtent = h), name = name)
    }
}
