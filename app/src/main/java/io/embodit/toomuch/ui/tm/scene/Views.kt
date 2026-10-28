package io.embodit.toomuch.ui.tm.scene

import com.pico.spatial.core.ecs.AnchorComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.anchor.AnchorTarget
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import io.embodit.toomuch.domain.engine.Engine
import io.embodit.toomuch.domain.engine.ItemId
import io.embodit.toomuch.domain.engine.OverlayModel
import io.embodit.toomuch.domain.engine.TrayLayout
import io.embodit.toomuch.domain.engine.TrayTask
import io.embodit.toomuch.domain.model.DefaultSpot
import io.embodit.toomuch.domain.model.FrameShape
import io.embodit.toomuch.domain.model.Placement
import io.embodit.toomuch.domain.model.Quat4
import io.embodit.toomuch.domain.model.RoomFrame
import io.embodit.toomuch.domain.model.RoomSize
import io.embodit.toomuch.domain.model.SourceId
import io.embodit.toomuch.domain.model.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Every mesh of the scene; [build] makes one per call so the scene can spread them over frames (ANR lesson). */
internal class Meshes(private val shared: SharedResources) {
    lateinit var cube: MeshResource
    lateinit var quad: MeshResource
    lateinit var ring: MeshResource
    lateinit var band: MeshResource
    lateinit var dash: MeshResource
    lateinit var shell: MeshResource
    lateinit var mug: MeshResource
    lateinit var keyRing: MeshResource
    lateinit var aim: MeshResource

    private val steps: List<() -> Unit> =
        listOf(
            { cube = shared.keep(MeshFactory.cube("tm:cube")) },
            { quad = shared.keep(MeshFactory.quadUv("tm:quad")) },
            { ring = shared.keep(MeshFactory.ringXY(0.486f, "tm:ring")) },
            { band = shared.keep(MeshFactory.unitBand("tm:band")) },
            { dash = shared.keep(MeshFactory.dashBand("tm:dash", 10)) },
            { shell = shared.keep(MeshFactory.sphereInside("tm:shell")) },
            { mug = shared.keep(MeshFactory.cylinder(0.04f, 0.095f, "tm:mug")) },
            { keyRing = shared.keep(MeshFactory.cylinder(0.016f, 0.004f, "tm:keyring", segments = 16)) },
            { aim = shared.keep(MeshFactory.ringXY(0.38f, "tm:aim", segments = 32)) },
        )
    private var next = 0

    /** Builds the next mesh; false when all are built. */
    fun build(): Boolean {
        if (next >= steps.size) return false
        steps[next++]()
        return next < steps.size
    }
}

/** One source card: edge plate, textured face, selection halo, three ripple rings; tap collider + hover. */
internal class SourceView(parent: Entity, val id: SourceId, m: Meshes, mats: SceneMaterials, face: Material) {
    val root: Entity = Entity().named("card:${id.tag}")
    private val edge = ModelEntity(m.cube, mats.cardEdge).named("card:${id.tag}:edge")
    private val faceEntity = ModelEntity(m.quad, face).named("card:${id.tag}:face")
    private val halo = ModelEntity(m.quad, mats.halo).named("card:${id.tag}:halo")
    private val rings = List(3) { ModelEntity(m.ring, mats.ripple).named("card:${id.tag}:ring$it") }
    private var shown = false
    private var faceMat: Material = face
    private var ringAlpha = FloatArray(3)

    /** Collapse factor 1 -> 0 over 1.5 s after unloading / quiet. */
    private var collapse = 1f

    init {
        parent.addChild(root)
        root.addChild(edge)
        root.addChild(faceEntity)
        root.addChild(halo)
        rings.forEach { root.addChild(it) }
        edge.setLocal(position = Vec3(0f, 0f, -0.006f), scale = Vec3(0.14f, 0.14f, 0.01f))
        faceEntity.setLocal(position = Vec3(0f, 0f, 0.0005f), scale = Vec3(0.12f, 0.12f, 1f))
        halo.setLocal(position = Vec3(0f, 0f, -0.013f), scale = Vec3(0.165f, 0.165f, 1f))
        halo.setOpacity(0f)
        rings.forEach {
            it.setLocal(position = Vec3(0f, 0f, -0.02f), scale = Vec3(0.1f, 0.1f, 1f))
            it.setOpacity(0f)
        }
        root.tapBox(Vec3(0.17f, 0.17f, 0.05f))
        runCatching { root.components.set(HoverEffectComponent()) }
        root.enabled = false
    }

    fun isMe(e: Entity): Boolean = e.id == root.id || e.id == edge.id || e.id == faceEntity.id || e.id == halo.id

    fun setFace(mat: Material) {
        if (mat === faceMat) return
        faceMat = mat
        faceEntity.setMaterial(mat)
    }

    fun place(position: Vec3, yaw: Float) {
        root.setLocal(position = position, rotation = Quat4.axisAngle(Vec3.UP, yaw))
    }

    fun show(visible: Boolean) {
        if (visible == shown) return
        shown = visible
        root.enabled = visible
    }

    /** Halo: 0 = none, otherwise the opacity (selected 1, breathing 0.5 - 0.8, faint 0.35). */
    fun halo(alpha: Float) = halo.setOpacity(alpha)

    /** Whole card see-through while the head is inside it (D14). */
    fun seeThrough(on: Boolean) = root.setOpacity(if (on) 0.3f else 1f)

    /**
     * Ripples (design `entities3d.ripple`): [count] rings 1..3, out to [maxR], period 5 s, line <= 1 cm,
     * alpha <= 0.6; [collapsing] pulls them into the card in 1.5 s and stops them; [count] 0 hides them.
     */
    fun ripples(phaseS: Float, count: Int, maxR: Float, collapsing: Boolean, dt: Float) {
        collapse = if (collapsing) (collapse - dt / 1.5f).coerceAtLeast(0f) else (collapse + dt / 1.5f).coerceAtMost(1f)
        for (i in 0 until 3) {
            val on = i < count && collapse > 0f
            val goal = if (on) 1f else 0f
            // a ring joining or leaving fades over 2 s (no jumps, safety V1)
            ringAlpha[i] = if (goal > ringAlpha[i]) minOf(goal, ringAlpha[i] + dt / 2f) else maxOf(goal, ringAlpha[i] - dt / 2f)
            val n = count.coerceAtLeast(1)
            val p = ((phaseS / Engine.RIPPLE_PERIOD_S + i.toFloat() / n) % 1f)
            val r = (0.07f + (maxR - 0.07f) * p) * collapse
            val a = 0.6f * (1f - p) * ringAlpha[i] * collapse * smoothIn(p)
            rings[i].setLocal(scale = Vec3(2f * r, 2f * r, 1f))
            rings[i].setOpacity(a)
        }
    }

    /** Fades a ring in over its first 10 % so it does not pop at the card edge. */
    private fun smoothIn(p: Float): Float = (p / 0.1f).coerceIn(0f, 1f)

    fun destroy() = runCatching { root.destroy(true) }
}

/** The virtual room: 12 edge lines, 6 collider faces, the six default-spot outlines. */
internal class RoomView(private val parent: Entity, private val m: Meshes, private val mats: SceneMaterials) {
    private val root = Entity().named("room")
    private val edges = List(12) { Strip(root, m.band, mats.roomLine, "room:edge$it") }
    private val faces = List(6) { Entity().named("room:face$it") }
    private val frames = HashMap<SourceId, List<Strip>>()
    private var linesShown = false
    private var key: Pair<RoomSize, RoomFrame>? = null
    var frameCenters: Map<SourceId, Vec3> = emptyMap()
        private set

    init {
        parent.addChild(root)
        faces.forEach { root.addChild(it) }
        for (id in SourceId.entries) frames[id] = List(if (id == SourceId.CLOCK) 12 else 4) { Strip(root, m.dash, mats.frameGrey, "room:spot:${id.tag}:$it") }
    }

    fun isFace(e: Entity): Boolean = faces.any { it.id == e.id }

    /** Rebuilds lines, colliders and outlines when the size or the frame changed. */
    fun sync(size: RoomSize, frame: RoomFrame, spots: List<DefaultSpot>) {
        if (key == size to frame) return
        key = size to frame
        val hw = size.width / 2f
        val hl = size.length / 2f
        val y0 = size.floorOffset
        val y1 = y0 + size.height
        val c = listOf(Vec3(-hw, y0, -hl), Vec3(hw, y0, -hl), Vec3(hw, y0, hl), Vec3(-hw, y0, hl)).map { frame.toStage(it) }
        val t = c.map { Vec3(it.x, y1, it.z) }
        val lines = listOf(c[0] to c[1], c[1] to c[2], c[2] to c[3], c[3] to c[0], t[0] to t[1], t[1] to t[2], t[2] to t[3], t[3] to t[0], c[0] to t[0], c[1] to t[1], c[2] to t[2], c[3] to t[3])
        val center = frame.toStage(Vec3(0f, (y0 + y1) / 2f, 0f))
        if (linesShown) lines.forEachIndexed { i, (a, b) -> edges[i].draw(a, b, 0.01f, center - (a + b) * 0.5f) }
        pendingLines = lines
        pendingCenter = center
        // colliders: 2 cm slabs on the six faces
        val th = 0.02f
        val slabs =
            listOf(
                Vec3(0f, (y0 + y1) / 2f, -hl) to Vec3(size.width, size.height, th),
                Vec3(0f, (y0 + y1) / 2f, hl) to Vec3(size.width, size.height, th),
                Vec3(-hw, (y0 + y1) / 2f, 0f) to Vec3(th, size.height, size.length),
                Vec3(hw, (y0 + y1) / 2f, 0f) to Vec3(th, size.height, size.length),
                Vec3(0f, y0, 0f) to Vec3(size.width, th, size.length),
                Vec3(0f, y1, 0f) to Vec3(size.width, th, size.length),
            )
        slabs.forEachIndexed { i, (lc, sz) ->
            faces[i].setLocal(position = frame.toStage(lc), rotation = Quat4.axisAngle(Vec3.UP, frame.yaw))
            faces[i].tapBox(sz)
        }
        // outlines
        val centers = HashMap<SourceId, Vec3>()
        for (spot in spots) {
            val strips = frames[spot.source] ?: continue
            val n = frame.dirToStage(spot.normal).normalized()
            val up = if (kotlin.math.abs(spot.normal.y) > 0.5f) frame.ahead else Vec3.UP
            val right = up.cross(n).normalized()
            val cc = frame.toStage(spot.frameCenter) + n * 0.01f
            centers[spot.source] = cc
            outline(spot, strips, cc, right, up, n)
        }
        frameCenters = centers
    }

    private var pendingLines: List<Pair<Vec3, Vec3>> = emptyList()
    private var pendingCenter = Vec3.ZERO

    private fun outline(spot: DefaultSpot, strips: List<Strip>, c: Vec3, right: Vec3, up: Vec3, n: Vec3) {
        frameGeometry[spot.source] = Triple(spot, Triple(c, right, up), n)
    }

    private val frameGeometry = HashMap<SourceId, Triple<DefaultSpot, Triple<Vec3, Vec3, Vec3>, Vec3>>()
    private var framesShown = false

    fun showLines(on: Boolean) {
        if (on == linesShown) return
        linesShown = on
        if (on) pendingLines.forEachIndexed { i, (a, b) -> edges[i].draw(a, b, 0.01f, pendingCenter - (a + b) * 0.5f) } else edges.forEach { it.hide() }
    }

    /** Shows the six outlines; [current] breathes in the interaction colour (6 s period, 0.5 - 0.8). */
    fun showFrames(on: Boolean, current: SourceId?, pinned: Set<SourceId>, timeS: Float) {
        if (!on) {
            if (framesShown) frames.values.flatten().forEach { it.hide() }
            framesShown = false
            return
        }
        framesShown = true
        for ((id, strips) in frames) {
            val g = frameGeometry[id]
            if (g == null || id in pinned && id != current) {
                strips.forEach { it.hide() }
                continue
            }
            val (spot, basis, n) = g
            val (c, right, up) = basis
            val hw = spot.frameW / 2f
            val hh = spot.frameH / 2f
            if (spot.shape == FrameShape.CIRCLE) {
                for (i in strips.indices) {
                    val a0 = 2 * PI * i / strips.size
                    val a1 = 2 * PI * (i + 1) / strips.size
                    val p0 = c + right * (cos(a0).toFloat() * hw) + up * (sin(a0).toFloat() * hw)
                    val p1 = c + right * (cos(a1).toFloat() * hw) + up * (sin(a1).toFloat() * hw)
                    strips[i].draw(p0, p1, 0.008f, n)
                }
            } else {
                val a = c - right * hw - up * hh
                val b = c + right * hw - up * hh
                val cc = c + right * hw + up * hh
                val d = c - right * hw + up * hh
                strips[0].draw(a, b, 0.008f, n)
                strips[1].draw(b, cc, 0.008f, n)
                strips[2].draw(cc, d, 0.008f, n)
                strips[3].draw(d, a, 0.008f, n)
            }
            val active = id == current
            strips.forEach {
                it.setMaterial(if (active) mats.frameActive else mats.frameGrey)
                it.opacity(if (active) 0.65f + 0.15f * sin(2 * PI * timeS / 6.0).toFloat() else 1f)
            }
        }
    }

    fun destroy() = runCatching { root.destroy(true) }
}

/** The tray: board, hook, three outlines, eight things and the keys (design `entities3d.tray*`). */
internal class TrayView(parent: Entity, private val m: Meshes, private val mats: SceneMaterials) {
    val root: Entity = Entity().named("tray")
    private val board = ModelEntity(m.cube, mats.wood).named("tray:board")
    private val hookPost = ModelEntity(m.cube, mats.hook).named("tray:hook")
    private val hookArm = ModelEntity(m.cube, mats.hook).named("tray:hookArm")
    private val outlines = HashMap<ItemId, List<Strip>>()
    private val items = HashMap<ItemId, Entity>()
    private val bodies = HashMap<ItemId, Entity>()
    private var shown = false
    private var pose: Placement.TrayPose? = null

    init {
        parent.addChild(root)
        root.addChild(board)
        root.addChild(hookPost)
        root.addChild(hookArm)
        board.setLocal(position = Vec3(0f, -TrayLayout.BOARD_T / 2f, 0f), scale = Vec3(TrayLayout.BOARD_W, TrayLayout.BOARD_T, TrayLayout.BOARD_D))
        val h = TrayLayout.hook
        hookPost.setLocal(position = Vec3(h.x, 0.03f, h.z - 0.02f), scale = Vec3(0.012f, 0.06f, 0.012f))
        hookArm.setLocal(position = Vec3(h.x, 0.055f, h.z), scale = Vec3(0.01f, 0.01f, 0.04f))
        for ((id, f) in TrayLayout.returnFrames) {
            val n = if (id == ItemId.MUG) 8 else 4
            val strips = List(n) { Strip(root, m.band, mats.traceGrey, "tray:outline:${id.tag}:$it") }
            outlines[id] = strips
            val (w, _, d) = TrayLayout.size[id]!!
            val y = 0.002f
            if (id == ItemId.MUG) {
                for (i in 0 until n) {
                    val a0 = 2 * PI * i / n
                    val a1 = a0 + PI / n
                    val r = 0.045f
                    strips[i].draw(Vec3(f.x + cos(a0).toFloat() * r, y, f.z + sin(a0).toFloat() * r), Vec3(f.x + cos(a1).toFloat() * r, y, f.z + sin(a1).toFloat() * r), 0.006f, Vec3.UP)
                }
            } else {
                val hw = w / 2f + 0.005f
                val hd = d / 2f + 0.005f
                val c = listOf(Vec3(f.x - hw, y, f.z - hd), Vec3(f.x + hw, y, f.z - hd), Vec3(f.x + hw, y, f.z + hd), Vec3(f.x - hw, y, f.z + hd))
                for (i in 0 until 4) strips[i].draw(c[i], c[(i + 1) % 4], 0.006f, Vec3.UP)
            }
        }
        for (id in ItemId.entries) {
            val holder = Entity().named("item:${id.tag}")
            root.addChild(holder)
            val (w, hgt, d) = TrayLayout.size[id]!!
            when (id) {
                ItemId.MUG -> {
                    val body = ModelEntity(m.mug, mats.mug).named("item:${id.tag}:body")
                    holder.addChild(body)
                    bodies[id] = body
                }
                ItemId.KEYS -> {
                    val ring = ModelEntity(m.keyRing, mats.brass).named("item:keys:ring")
                    holder.addChild(ring)
                    ring.setLocal(position = Vec3(-0.025f, -0.002f, 0f))
                    for (k in 0 until 3) {
                        val key = ModelEntity(m.cube, mats.brass).named("item:keys:$k")
                        holder.addChild(key)
                        val a = (k - 1) * 0.45f
                        key.setLocal(position = Vec3(0.012f + 0.028f * cos(a), 0f, 0.028f * sin(a)), rotation = Quat4.axisAngle(Vec3.UP, -a), scale = Vec3(0.06f, 0.004f, 0.012f))
                    }
                }
                else -> {
                    val body = ModelEntity(m.cube, materialOf(id)).named("item:${id.tag}:body")
                    holder.addChild(body)
                    body.setLocal(position = Vec3(0f, hgt / 2f, 0f), scale = Vec3(w, hgt, d))
                    bodies[id] = body
                }
            }
            holder.tapBox(Vec3(w + 0.02f, hgt + 0.03f, d + 0.02f), Vec3(0f, hgt / 2f, 0f))
            runCatching { holder.components.set(HoverEffectComponent()) }
            items[id] = holder
        }
        root.enabled = false
    }

    private fun materialOf(id: ItemId): Material =
        when (id) {
            ItemId.ENVELOPE1, ItemId.ENVELOPE2 -> mats.envelope
            ItemId.NOTEBOOK -> mats.notebook
            ItemId.SCARF -> mats.scarf
            ItemId.GLASSES -> mats.glasses
            ItemId.BOOK -> mats.book
            ItemId.REMOTE -> mats.remote
            else -> mats.mug
        }

    /** Which tray thing an entity belongs to (holder or body). */
    fun itemOf(e: Entity): ItemId? {
        for ((id, h) in items) if (h.id == e.id) return id
        for ((id, b) in bodies) if (b.id == e.id) return id
        return null
    }

    fun place(p: Placement.TrayPose) {
        pose = p
        val tilt = Math.toRadians(Placement.TRAY_TILT_DEG.toDouble()).toFloat()
        root.setLocal(position = p.center, rotation = Quat4.axisAngle(Vec3.UP, p.yaw) * Quat4.axisAngle(Vec3.RIGHT, tilt))
    }

    fun show(on: Boolean) {
        if (on == shown) return
        shown = on
        root.enabled = on
    }

    /** Draws the task's things where the task says they are. */
    fun sync(task: TrayTask?) {
        for ((id, holder) in items) {
            val it = task?.items?.get(id)
            if (it == null) {
                holder.enabled = id != ItemId.KEYS
                if (id != ItemId.KEYS) {
                    val spot = TrayLayout.fixedSpots[id] ?: TrayLayout.candidateSlots[ItemId.entries.indexOf(id).coerceIn(0, 3)]
                    holder.setLocal(position = Vec3(spot.x, 0f, spot.z), rotation = Quat4.axisAngle(Vec3.UP, spot.yaw))
                }
                continue
            }
            holder.enabled = it.visible
            holder.setLocal(position = Vec3(it.x, it.lift, it.z), rotation = Quat4.axisAngle(Vec3.UP, it.yaw))
        }
    }

    /** Stage-space box around the tray (for the unload card's clearance test). */
    fun box(): Pair<Vec3, Vec3>? {
        val p = pose ?: return null
        val cs = p.corners()
        return Vec3(cs.minOf { it.x }, cs.minOf { it.y }, cs.minOf { it.z }) to Vec3(cs.maxOf { it.x }, cs.maxOf { it.y } + 0.12f, cs.maxOf { it.z })
    }

    fun destroy() = runCatching { root.destroy(true) }
}

/** The head-locked layer (design `overlay`): haze shell + six blob quads on a camera anchor. */
internal class OverlayView(parent: Entity, m: Meshes, mats: SceneMaterials, blobMats: List<Material>) {
    private val anchor = Entity().named("overlay")
    private val layer = Entity().named("overlay:layer")
    private val shell = ModelEntity(m.shell, mats.haze).named("overlay:haze")
    private val blobs = List(OverlayModel.SLOTS.size) { i -> ModelEntity(m.quad, blobMats[OverlayModel.SLOTS[i].colorIndex % blobMats.size]).named("overlay:blob$i") }
    private var on = true

    init {
        anchor.components.set(AnchorComponent(AnchorTarget.createCameraTarget()))
        parent.addChild(anchor)
        anchor.addChild(layer)
        layer.addChild(shell)
        val d = OverlayModel.SHELL_RADIUS_M * 2f
        shell.setLocal(scale = Vec3(d, d, d))
        shell.setOpacity(0f)
        runCatching { shell.components.set(SceneMaterials.overlaySort()) }
        val r = OverlayModel.SHELL_RADIUS_M * 0.975f
        val size = 2f * r * tan(Math.toRadians(OverlayModel.BLOB_RADIUS_DEG.toDouble())).toFloat()
        for (b in blobs) {
            layer.addChild(b)
            b.setLocal(scale = Vec3(size, size, 1f))
            b.setOpacity(0f)
            runCatching { b.components.set(SceneMaterials.overlaySort()) }
        }
        show(false)
    }

    private fun show(visible: Boolean) {
        if (visible == on) return
        on = visible
        layer.enabled = visible
    }

    /** Writes the model's haze and blobs; hides the whole layer while nothing shows. */
    fun sync(model: OverlayModel) {
        val any = model.haze > 0.001f || model.blobs.any { it.alpha > 0.001f }
        show(any)
        if (!any) return
        shell.setOpacity(model.haze)
        val r = OverlayModel.SHELL_RADIUS_M * 0.975f
        for ((i, b) in model.blobs.withIndex()) {
            val e = blobs[i]
            val az = Math.toRadians(b.az.toDouble()).toFloat()
            val el = Math.toRadians(b.el.toDouble()).toFloat()
            // head frame: x right, y up, -z ahead
            val dir = Vec3(sin(az) * cos(el), sin(el), -cos(az) * cos(el))
            e.setLocal(position = dir * r, rotation = Quat4.fromTo(Vec3(0f, 0f, 1f), -dir))
            e.setOpacity(b.alpha)
            e.enabled = b.alpha > 0.001f
        }
    }

    fun destroy() = runCatching { anchor.destroy(true) }
}

/** Controller aiming aid in S04: a 40 % ghost card at the landing point and a landing ring. */
internal class AimView(parent: Entity, m: Meshes, mats: SceneMaterials) {
    private val root = Entity().named("aim")
    private val ghost = ModelEntity(m.cube, mats.ghost).named("aim:ghost")
    private val ring = ModelEntity(m.aim, mats.aimRing).named("aim:ring")
    private var on = true

    init {
        parent.addChild(root)
        root.addChild(ghost)
        root.addChild(ring)
        ghost.setLocal(position = Vec3(0f, 0f, 0.02f), scale = Vec3(0.12f, 0.12f, 0.01f))
        ring.setLocal(scale = Vec3(0.06f, 0.06f, 1f))
        show(false)
    }

    fun show(visible: Boolean) {
        if (visible == on) return
        on = visible
        root.enabled = visible
    }

    /** At [point], the face turned along [normal] (towards the user). */
    fun place(point: Vec3, normal: Vec3) {
        root.setLocal(position = point, rotation = Quat4.fromTo(Vec3(0f, 0f, 1f), normal.normalized()))
    }

    fun destroy() = runCatching { root.destroy(true) }
}
