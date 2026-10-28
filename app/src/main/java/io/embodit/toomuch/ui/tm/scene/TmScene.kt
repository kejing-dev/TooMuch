package io.embodit.toomuch.ui.tm.scene

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.ui.foundation.gesture.SpatialDragValue
import com.pico.spatial.ui.foundation.gesture.SpatialTapValue
import io.embodit.toomuch.domain.engine.Engine
import io.embodit.toomuch.domain.engine.EngineEvent
import io.embodit.toomuch.domain.engine.ItemId
import io.embodit.toomuch.domain.engine.Mode
import io.embodit.toomuch.domain.engine.OverlayModel
import io.embodit.toomuch.domain.engine.TrayTask
import io.embodit.toomuch.domain.model.InputMode
import io.embodit.toomuch.domain.model.LevelHead
import io.embodit.toomuch.domain.model.Placement
import io.embodit.toomuch.domain.model.PointerRay
import io.embodit.toomuch.domain.model.Quat4
import io.embodit.toomuch.domain.model.RoomRules
import io.embodit.toomuch.domain.model.SourceId
import io.embodit.toomuch.domain.model.Vec3
import io.embodit.toomuch.platform.TmGraph
import io.embodit.toomuch.platform.TmLog
import io.embodit.toomuch.platform.audio.ObjectSoundOut
import io.embodit.toomuch.platform.audio.PoolSoundOut
import io.embodit.toomuch.platform.audio.SoundOut
import io.embodit.toomuch.platform.input.ControllerSample
import io.embodit.toomuch.platform.tracking.HeadPose
import io.embodit.toomuch.ui.tm.MenuPage
import io.embodit.toomuch.ui.tm.Screen
import io.embodit.toomuch.ui.tm.TaskStage
import io.embodit.toomuch.ui.tm.TmEvent
import io.embodit.toomuch.ui.tm.TmUiState
import io.embodit.toomuch.ui.tm.TmViewModel
import io.embodit.toomuch.ui.tm.debug.TmProbe
import io.embodit.toomuch.ui.theme.TmColors
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** The framework instantiates the system itself, so the running scene is reached through this holder. */
internal object ActiveScene {
    @Volatile var scene: TmScene? = null
}

/** The one per-frame ECS system of the app: steps the engine, sound, layer, ripples, tray, panels. */
class TmFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveScene.scene?.onFrame(context)
    }
}

/**
 * The 3D side: source cards with ripples and their sounds, the virtual room, the tray, the head-locked
 * layer, the controller aim, and the placement of the pre-built panels. Compose state arrives through
 * [render]; per-frame work happens in [onFrame] only (the engine, owned by the ViewModel, is stepped
 * here). Everything started in [attach] is stopped in [detach]. Main thread only.
 */
class TmScene(private val vm: TmViewModel) : TmProbe.SceneProbe {
    private val sink: (TmEvent) -> Unit = vm::onEvent
    private val engine: Engine = vm.engine

    /** Panel id -> the panel is in view: a pooled panel composes its content only then. */
    val panelShown = mutableStateMapOf<String, Boolean>()

    /** Bumped to make `SpatialView.update` run again while panel entities are still missing. */
    val bindTick = mutableIntStateOf(0)

    private val main = Handler(Looper.getMainLooper())
    private val headTracker = TmGraph.head
    private val controller = TmGraph.controller
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var buildJob: Job? = null

    private var content: SpatialViewContent? = null
    private var stageRoot: Entity? = null
    private var contentProbe: Entity? = null
    private var shared: SharedResources? = null
    private var mats: SceneMaterials? = null
    private var meshes: Meshes? = null
    private var rig: PanelRig? = null
    private val sources = HashMap<SourceId, SourceView>()
    private var room: RoomView? = null
    private var tray: TrayView? = null
    private var overlay: OverlayView? = null
    private var aim: AimView? = null
    private var sound: SoundOut? = null
    private var soundName = "object"
    private val faces = HashMap<SourceId, Material>()
    private var testFace: Material? = null
    private var uvTest = false
    private var attached = false
    private var nextFrame: CompletableDeferred<Unit>? = null

    private val bodyFollower = Follower("body", BODY_OFFSET, BODY_LEAN)

    private var ui = TmUiState()
    private var head = HeadPose(Vec3.ZERO, Quat4.IDENTITY)
    private var hasHead = false
    private var lastFrameMs = 0L
    private var lastHeadSentMs = 0L
    private var lastAudioMs = 0L
    private var lastStateMs = 0L
    private var stateLines = false
    private var lastSerial = -1
    private var frontPlaced = false
    private var lastFront: Any? = null
    private var panelPressed = false
    private var lastPanelPressMs = NEVER
    private var paused = false

    // controller
    private var controllerSeenMs = NEVER
    private var lastTrigger = false
    private var aimDistance = 2.5f
    private var aimPoint: Vec3? = null
    private var stickYawing = false
    private var trayHeldByController = false

    // system drag on the tray
    private var dragItem: ItemId? = null
    private var dragPxToM = 0.0008f
    private var dragLocal = Vec3.ZERO
    private var dragMoved = false

    fun onPanelPointer(pressed: Boolean) {
        panelPressed = pressed
        lastPanelPressMs = SystemClock.elapsedRealtime()
    }

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached || stageRoot != null) detach()
        val t0 = SystemClock.elapsedRealtime()
        this.content = content
        val root = Entity().named("stageRoot")
        content.addEntity(root)
        val probe = Entity().named("contentProbe")
        content.addEntity(probe)
        contentProbe = probe
        // an entity added to the SpatialView is not at the Stage origin: move the root once (Spatial Ruler)
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        TmLog.i("STAGE root offset=${TmLog.pos(offset.domain())}")
        val rig = PanelRig(root) { id, shown -> panelShown[id] = shown }
        this.rig = rig
        for (id in PanelSpec.pool) {
            val (w, h) = PanelSpec.size(id)
            rig.world(id, w, h)
        }
        headTracker.start()
        controller.start()
        TmGraph.anchors.start()
        ActiveScene.scene = this
        bindPanels(attachments)
        bodyFollower.reset()
        buildJob =
            scope.launch {
                try {
                    build(root, t0)
                } catch (t: Throwable) {
                    if (t !is CancellationException) TmLog.e("SCENE build failed: $t", t)
                    throw t
                }
            }
    }

    /** Suspends until the next rendered frame (one mesh / texture / sound per frame: ANR lesson). */
    private suspend fun frame() {
        val d = CompletableDeferred<Unit>()
        nextFrame = d
        d.await()
    }

    private suspend fun build(root: Entity, t0: Long) {
        val sh = SharedResources()
        shared = sh
        val m = SceneMaterials(sh)
        mats = m
        frame()
        val meshes = Meshes(sh)
        this.meshes = meshes
        while (meshes.build()) frame()
        frame()
        val ctx = TmGraph.appContext
        for (id in SourceId.entries) {
            faces[id] = runCatching { m.textured(sh.keep(TextureResource.create(Textures.cardFace(ctx!!, id)))) }.onFailure { TmLog.e("TEXTURE card ${id.tag} failed: $it") }.getOrElse { m.cardEdge }
            frame()
        }
        testFace = runCatching { m.textured(sh.keep(TextureResource.create(Textures.orientationTest()))) }.getOrNull()
        val blobTex = runCatching { sh.keep(TextureResource.create(Textures.blob())) }.onFailure { TmLog.e("TEXTURE blob failed: $it") }.getOrNull()
        val blobMats = TmColors.Blob.map { c -> if (blobTex != null) m.blob(blobTex, c) else m.haze }
        frame()
        room = RoomView(root, meshes, m)
        frame()
        for (id in SourceId.entries) {
            sources[id] = SourceView(root, id, meshes, m, faces[id] ?: m.cardEdge)
            frame()
        }
        tray = TrayView(root, meshes, m)
        frame()
        overlay = OverlayView(root, meshes, m, blobMats)
        aim = AimView(root, meshes, m)
        frame()
        sound = makeSound(root, ui.audio)
        while (sound?.loadNext() == true) frame()
        attached = true
        TmLog.i("SCENE attached build_ms=${SystemClock.elapsedRealtime() - t0}")
        render(ui, force = true)
        sink(TmEvent.SceneReady)
    }

    private fun makeSound(root: Entity, name: String): SoundOut? =
        runCatching {
                if (name == "soundpool") PoolSoundOut(TmGraph.appContext!!) else ObjectSoundOut(root)
            }
            .onFailure { TmLog.e("AUDIO backend $name failed: $it", it) }
            .getOrNull()
            .also {
                soundName = it?.name ?: "none"
                TmLog.i("AUDIO backend=$soundName")
            }

    fun bindPanels(attachments: SpatialViewAttachments) {
        val rig = rig ?: return
        val complete = rig.bind(attachments, PanelSpec.pool, SystemClock.elapsedRealtime())
        if (!complete) {
            main.removeCallbacks(rebind)
            main.postDelayed(rebind, REBIND_DELAY_MS)
        }
    }

    private val rebind = Runnable { if (stageRoot != null) bindTick.intValue++ }

    fun pause() {
        paused = true
        sound?.setRunning(false)
    }

    fun resume() {
        paused = false
        sound?.setRunning(true)
    }

    fun detach() {
        if (!attached && stageRoot == null) return
        attached = false
        buildJob?.cancel()
        buildJob = null
        nextFrame = null
        if (ActiveScene.scene === this) ActiveScene.scene = null
        main.removeCallbacksAndMessages(null)
        headTracker.stop()
        controller.stop()
        TmGraph.anchors.stop()
        panelShown.clear()
        runCatching { sound?.release() }
        sound = null
        runCatching { rig?.release() }
        sources.values.forEach { it.destroy() }
        sources.clear()
        room?.destroy()
        tray?.destroy()
        overlay?.destroy()
        aim?.destroy()
        runCatching { stageRoot?.destroy(true) }
        runCatching { contentProbe?.destroy(true) }
        shared?.close()
        shared = null
        stageRoot = null
        contentProbe = null
        content = null
        mats = null
        meshes = null
        rig = null
        room = null
        tray = null
        overlay = null
        aim = null
        faces.clear()
        hasHead = false
        TmLog.i("SCENE detached")
    }

    // =========================================================================================
    // model -> entities
    // =========================================================================================

    fun render(state: TmUiState, force: Boolean = false) {
        val previous = ui
        ui = state
        if (state.audio != soundName && attached) switchSound(state.audio)
        if (!attached) return
        if (force || state.traySerial != lastSerial || state.tray != previous.tray) {
            lastSerial = state.traySerial
            state.tray?.let { tray?.place(it) }
        }
        val front = state.front
        if (front != lastFront) {
            lastFront = front
            frontPlaced = false
        }
        syncPanels(state)
    }

    private fun switchSound(name: String) {
        val root = stageRoot ?: return
        sound?.release()
        sound = makeSound(root, name)
        scope.launch {
            while (sound?.loadNext() == true) frame()
        }
    }

    private fun syncPanels(state: TmUiState) {
        val rig = rig ?: return
        if (!state.menuOpen) rig[PanelSpec.MENU]?.hide()
        if (state.front == null) rig[PanelSpec.FRONT]?.hide()
        if (state.cardFor == null) rig[PanelSpec.UNLOAD]?.hide()
        if (!(state.menuOpen && state.menuPage == MenuPage.ROOM)) rig[PanelSpec.ROOM_TAG]?.hide()
    }

    // =========================================================================================
    // per frame
    // =========================================================================================

    fun onFrame(context: SceneUpdateContext) {
        val root = stageRoot ?: return
        if (root.scene == null || context.scene != root.scene) return
        nextFrame?.let {
            nextFrame = null
            it.complete(Unit)
        }
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameMs == 0L) 0.016f else ((now - lastFrameMs) / 1000f).coerceIn(0.001f, 0.1f)
        lastFrameMs = now
        headTracker.pose()?.let {
            head = it
            hasHead = true
        }
        val rig = rig ?: return
        rig.onFrame(now)
        if (hasHead) rig.stowBehind(head)
        val state = ui
        if (hasHead && head.position.length > 1e-3f) {
            val hold = panelPressed || (lastPanelPressMs != NEVER && now - lastPanelPressMs < PANEL_HOLD_MS) || state.menuOpen
            bodyFollower.follow(head, now, hold)
            placeBodyFamily(state, rig)
        }
        if (hasHead && now - lastHeadSentMs >= HEAD_SAMPLE_MS) {
            lastHeadSentMs = now
            sink(TmEvent.HeadSample(head.position, head.forward))
            pollBlock()
        }
        if (!attached) {
            rig.faceViewer(head.position)
            return
        }
        // input mode
        val sample = controller.sample()
        if (sample != null) {
            if (controllerSeenMs == NEVER) sink(TmEvent.InputDetected(InputMode.CONTROLLER))
            controllerSeenMs = now
        } else if (controllerSeenMs != NEVER && now - controllerSeenMs > CONTROLLER_GONE_MS) {
            controllerSeenMs = NEVER
            sink(TmEvent.InputDetected(InputMode.HANDS))
        }
        val useController = state.input == InputMode.CONTROLLER && sample != null
        // engine (owned by the ViewModel): clocks, gains, pulses, overlay, task
        val lamp = state.pinned[SourceId.LAMP]?.position?.let { p -> LevelHead.of(head.position, head.forward).anglesOf(p) }
        val events = vm.onFrame(dt, lamp)
        for (e in events) if (e is EngineEvent.Pulse) sound?.shot(e.source, e.variant, engine)
        val st = ui
        syncRoom(st, now)
        syncSources(st, dt)
        syncTray(st, if (useController) sample else null)
        overlay?.sync(engine.overlay)
        syncAim(st, if (useController) sample else null, dt)
        stick(st, if (useController) sample else null, dt)
        placeWorldPanels(st, rig)
        if (now - lastAudioMs >= AUDIO_MS && !paused) {
            lastAudioMs = now
            for ((id, p) in st.pinned) sound?.place(id, p.position)
            sound?.apply(engine, head)
        }
        if (stateLines && now - lastStateMs >= 1000L) {
            lastStateMs = now
            stateLine().forEach { TmLog.i(it) }
        }
        rig.faceViewer(head.position)
    }

    private var blockChecked = false
    private var blockSinceMs = NEVER

    /** The provider's support state turns to SUPPORTED by itself while the Stage opens: asked for 3 s before blocking. */
    private fun pollBlock() {
        val reason = headTracker.blockReason()
        if (reason == null) {
            blockChecked = true
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!blockChecked) {
            if (blockSinceMs == NEVER) blockSinceMs = now
            if (now - blockSinceMs < BLOCK_GRACE_MS) return
        }
        sink(TmEvent.Blocked(reason))
    }

    // ---- room

    private fun syncRoom(state: TmUiState, now: Long) {
        val room = room ?: return
        val frame = state.roomFrame ?: return
        val spots = RoomRules.spots(state.room, frame)
        room.sync(state.room, frame, spots)
        room.showLines(state.roomLinesShown)
        val pinning = state.screen == Screen.PINNING
        room.showFrames(pinning, if (pinning) state.adjusting ?: state.current else null, state.pinned.keys, now / 1000f)
    }

    // ---- source cards, ripples

    private fun syncSources(state: TmUiState, dt: Float) {
        val timeS = SystemClock.elapsedRealtime() / 1000f
        for ((id, view) in sources) {
            val p = state.pinned[id]
            if (p == null) {
                view.show(false)
                continue
            }
            view.show(state.screen != Screen.STARTING && state.screen != Screen.UNAVAILABLE)
            view.place(p.position, p.yaw + liveYaw(id))
            val mat = if (uvTest) testFace else faces[id]
            if (mat != null) view.setFace(mat)
            // halo: selected (adjusting) solid, unload targets faint, a moved-away unload card breathes
            val halo =
                when {
                    state.screen == Screen.PINNING && state.adjusting == id -> 1f
                    state.screen == Screen.UNLOAD && state.cardFor == id && state.cardFront -> 0.65f + 0.15f * sin(2 * Math.PI * timeS / 6.0).toFloat()
                    state.screen == Screen.UNLOAD && state.cardFor == null && id !in state.unloaded -> 0.35f
                    else -> 0f
                }
            view.halo(halo)
            val near = hasHead && p.position.distanceTo(head.position) < SEE_THROUGH_M
            view.seeThrough(near)
            // ripples by the source's level
            val lvl = engine.sourceLevel(id)
            val collapsing = engine.mode == Mode.QUIET || (id in engine.unloaded && engine.mode in setOf(Mode.UNLOAD, Mode.REDO))
            val stopped = engine.mode == Mode.ENDING || engine.mode == Mode.SILENT
            val rl = lvl ?: if (engine.mode == Mode.PINNING) 0 else -1
            val count = if (stopped || rl < 0) 0 else RING_COUNT[rl.coerceIn(0, 4)]
            val maxR = 0.25f + 0.025f * rl.coerceIn(0, 4)
            view.ripples(engine.ripplePhaseS, if (collapsing) maxOf(count, 1) else count, maxR, collapsing, dt)
        }
    }

    private val liveYaws = HashMap<SourceId, Float>()

    private fun liveYaw(id: SourceId): Float = liveYaws[id] ?: 0f

    // ---- tray

    private fun syncTray(state: TmUiState, sample: ControllerSample?) {
        val tray = tray ?: return
        val shown = state.trayShown
        tray.show(shown)
        if (!shown) return
        val task = engine.task
        tray.sync(task)
        val pose = state.tray ?: return
        if (task == null) return
        val taskLive = state.screen == Screen.TASK || state.screen == Screen.REDO
        if (!taskLive || sample == null) {
            if (trayHeldByController) {
                task.release()
                trayHeldByController = false
            }
            lastTrigger = sample?.pressed == true || (sample?.trigger ?: 0f) > TRIGGER_ON
            return
        }
        val down = sample.pressed || sample.trigger > TRIGGER_ON
        val rising = down && !lastTrigger
        val falling = !down && lastTrigger
        lastTrigger = down
        val level = if (state.screen == Screen.TASK) engine.level() else 0
        if (rising && !rayHitsPanel(sample.ray)) {
            val item = rayItem(sample.ray, pose, task)
            if (item != null) {
                val drag = item.returnable && task.stage == TrayTask.Stage.RETURN
                task.press(item, drag)
                engine.userActed()
                trayHeldByController = task.holding
                TmLog.i("TRAY press ${item.tag} by=controller drag=$drag")
            }
        }
        if (trayHeldByController && task.isDragging) {
            pose.rayOnBoard(sample.ray)?.let { task.dragTo(it.x, it.z, level) }
        }
        if (falling && trayHeldByController) {
            task.release()
            trayHeldByController = false
        }
        if (!task.holding) trayHeldByController = false
    }

    /** The tray thing a controller ray points at (tray-local boxes, nearest along the ray). */
    private fun rayItem(ray: PointerRay, pose: Placement.TrayPose, task: TrayTask): ItemId? {
        var best: ItemId? = null
        var bestT = Float.MAX_VALUE
        val o = pose.toLocal(ray.origin)
        val far = pose.toLocal(ray.at(1f))
        val d = (far - o)
        for ((id, it) in task.items) {
            if (!it.visible) continue
            val (w, h, dd) = io.embodit.toomuch.domain.engine.TrayLayout.size[id] ?: continue
            val c = Vec3(it.x, it.lift + h / 2f, it.z)
            val t = rayBox(o, d, c, Vec3(w / 2f + 0.015f, h / 2f + 0.015f, dd / 2f + 0.015f)) ?: continue
            if (t < bestT) {
                bestT = t
                best = id
            }
        }
        return best
    }

    // ---- controller aim and stick (S04 / S04c)

    private fun syncAim(state: TmUiState, sample: ControllerSample?, dt: Float) {
        val aim = aim ?: return
        val pinning = state.screen == Screen.PINNING && state.adjusting == null && !state.menuOpen && state.current != null
        if (!pinning || sample == null) {
            aim.show(false)
            aimPoint = null
            if (state.screen != Screen.TASK && state.screen != Screen.REDO) lastTrigger = sample?.let { it.pressed || it.trigger > TRIGGER_ON } ?: false
            return
        }
        val frame = state.roomFrame ?: return
        // stick up / down pushes / pulls the landing point along the ray (0.5 - 6 m, 0.8 m/s)
        val y = sample.stickY
        val wall = RoomRules.hitRoom(state.room, frame, sample.ray)
        if (abs(y) > RoomRules.STICK_DEAD) {
            val base = aimPoint?.let { it.distanceTo(sample.ray.origin) } ?: wall?.first ?: aimDistance
            aimDistance = (base + y * RoomRules.PUSH_PULL_M_S * dt).coerceIn(RoomRules.AIM_MIN_M, RoomRules.AIM_MAX_M)
            aimFree = true
        }
        val t = if (aimFree) aimDistance else wall?.first ?: aimDistance
        if (!aimFree && wall != null) aimDistance = wall.first
        val point = sample.ray.at(t)
        val normal = if (!aimFree && wall != null) frame.dirToStage(wall.second) else (head.position - point).normalized()
        aimPoint = point
        aim.show(true)
        aim.place(point + normal * 0.01f, normal)
        val down = sample.pressed || sample.trigger > TRIGGER_ON
        val rising = down && !lastTrigger
        lastTrigger = down
        if (rising && !rayHitsPanel(sample.ray) && rayCard(sample.ray) == null) {
            TmLog.i("AIM pin at=${TmLog.pos(point)} free=$aimFree")
            sink(TmEvent.PinAt(point, fromController = true))
            aimFree = false
        }
    }

    private var aimFree = false

    /** Thumbstick x turns the card being adjusted about its vertical (D1: dead zone 0.2, 90 deg/s). */
    private fun stick(state: TmUiState, sample: ControllerSample?, dt: Float) {
        val id = state.adjusting
        if (state.screen != Screen.PINNING || id == null || sample == null) {
            if (stickYawing) settleYaw(state)
            return
        }
        val rate = RoomRules.stickYawRate(sample.stickX)
        if (rate != 0f) {
            liveYaws[id] = (liveYaws[id] ?: 0f) + rate * dt
            stickYawing = true
        } else if (stickYawing) {
            settleYaw(state)
        }
    }

    private fun settleYaw(state: TmUiState) {
        stickYawing = false
        for ((id, d) in liveYaws) {
            val p = state.pinned[id] ?: continue
            sink(TmEvent.CardYaw(id, p.yaw + d, settled = true))
        }
        liveYaws.clear()
    }

    /** The card a controller ray points at (a 0.1 m sphere around each card). */
    private fun rayCard(ray: PointerRay): SourceId? {
        var best: SourceId? = null
        var bestT = Float.MAX_VALUE
        for ((id, p) in ui.pinned) {
            val to = p.position - ray.origin
            val t = to.dot(ray.direction)
            if (t <= 0f) continue
            val miss = (to - ray.direction * t).length
            if (miss < 0.1f && t < bestT) {
                bestT = t
                best = id
            }
        }
        return best
    }

    /** True when a controller ray meets a shown panel first (its press belongs to the panel). */
    private fun rayHitsPanel(ray: PointerRay): Boolean {
        val rig = rig ?: return false
        for (slot in rig.all) {
            if (!slot.shown) continue
            val n = slot.rotation.rotate(Vec3(0f, 0f, 1f))
            val denom = ray.direction.dot(n)
            if (abs(denom) < 1e-4f) continue
            val t = (slot.position - ray.origin).dot(n) / denom
            if (t <= 0f) continue
            val p = ray.at(t) - slot.position
            val hw = slot.widthDp * PanelRig.DP_M * slot.scale / 2f
            val hh = slot.heightDp * PanelRig.DP_M * slot.scale / 2f
            if (abs(p.dot(slot.rotation.rotate(Vec3.RIGHT))) <= hw && abs(p.dot(slot.rotation.rotate(Vec3.UP))) <= hh) return true
        }
        return false
    }

    // ---- panels

    private fun placeBodyFamily(state: TmUiState, rig: PanelRig) {
        val pose = bodyFollower.pose
        val rot = bodyFollower.rotation
        val k = PanelSpec.BODY_SCALE
        rig[PanelSpec.BODY]?.place(pose.position, rot, k)
        if (state.menuOpen) {
            // stacked above the body panel, same depth: never in front of "Quiet now"
            val (_, bh) = PanelSpec.halfM(PanelSpec.BODY, k)
            val (_, mh) = PanelSpec.halfM(PanelSpec.MENU, PanelSpec.MENU_SCALE)
            rig[PanelSpec.MENU]?.place(pose.position + rot.rotate(Vec3(0f, bh + 0.02f + mh, 0f)), rot, PanelSpec.MENU_SCALE)
        }
    }

    private fun placeWorldPanels(state: TmUiState, rig: PanelRig) {
        // front card: once per layout, at the head's level pose of that moment (D9)
        val front = state.front
        if (front != null && !frontPlaced && hasHead) {
            frontPlaced = true
            val lh = LevelHead.of(head.position, head.forward)
            // over the tray (both 0.9 m ahead) the card rises to just under eye level so it clears the things on it
            val at = Placement.frontCard(lh).let { if (state.trayShown) Vec3(it.x, head.position.y - FRONT_OVER_TRAY_DROP_M, it.z) else it }
            rig[PanelSpec.FRONT]?.show(at, PanelSpec.FRONT_SCALE)
            TmLog.i("PANEL front ${front.name.lowercase(Locale.US)} at=${TmLog.pos(at)} overTray=${state.trayShown}")
        }
        // unload card where the ViewModel put it
        val cp = state.cardPos
        if (state.cardFor != null && cp != null) rig[PanelSpec.UNLOAD]?.show(cp, PanelSpec.UNLOAD_SCALE)
        // name labels above the cards, world text sized by distance (2.5 cm at 1 m, up to 6 cm)
        for (id in SourceId.entries) {
            val slot = rig[PanelSpec.label(id)] ?: continue
            val p = state.pinned[id]
            val visible = p != null && state.screen in LABEL_SCREENS
            if (!visible) {
                slot.hide()
                continue
            }
            val d = if (hasHead) p!!.position.distanceTo(head.position) else 1f
            val textM = (LABEL_TEXT_M * d).coerceIn(LABEL_TEXT_M, LABEL_TEXT_MAX_M)
            val k = textM / LABEL_TEXT_AT_SCALE1_M
            val (_, hh) = PanelSpec.halfM(PanelSpec.label(id), k)
            slot.show(p!!.position + Vec3(0f, 0.09f + hh, 0f), k)
        }
        // room size tag on the front wall while the room page is open
        val frame = state.roomFrame
        if (state.menuOpen && state.menuPage == MenuPage.ROOM && frame != null) {
            val at = frame.toStage(Vec3(0f, state.room.floorOffset + 1.4f, -state.room.length / 2f + 0.05f))
            rig[PanelSpec.ROOM_TAG]?.show(at, 2.0f)
        }
    }

    // =========================================================================================
    // system pointer (gaze + pinch, emulator mouse; controller presses on entities too)
    // =========================================================================================

    fun onSpatialTap(value: SpatialTapValue) {
        val target = value.targetEntity
        val name = runCatching { target?.getName() }.getOrNull() ?: "none"
        val stage = toStage(value.position.x, value.position.y, value.position.z)
        val state = ui
        val controllerMode = state.input == InputMode.CONTROLLER && controllerSeenMs != NEVER
        if (target == null) {
            TmLog.i("TAP none")
            return
        }
        val card = sources.entries.firstOrNull { it.value.isMe(target) }?.key ?: nearestCard(stage)
        val item = tray?.itemOf(target) ?: runCatching { target.getParent() }.getOrNull()?.let { tray?.itemOf(it) }
        when {
            card != null -> {
                TmLog.i("TAP card ${card.tag} stage=${TmLog.pos(stage)}")
                sink(TmEvent.CardTapped(card))
            }
            item != null -> {
                if (controllerMode) return
                val task = engine.task ?: return
                if (state.screen != Screen.TASK && state.screen != Screen.REDO) return
                if (dragMoved) return
                TmLog.i("TAP item ${item.tag}")
                task.peek(item)
                engine.userActed()
            }
            room?.isFace(target) == true -> {
                TmLog.i("TAP room ${name} stage=${TmLog.pos(stage)}")
                if (state.screen == Screen.PINNING && state.adjusting == null && !state.menuOpen) {
                    if (!controllerMode && stage != null) sink(TmEvent.PinAt(stage, fromController = false))
                } else {
                    sink(TmEvent.EmptyTapped)
                }
            }
            else -> TmLog.i("TAP other $name stage=${TmLog.pos(stage)}")
        }
    }

    /** Hands: a tap within 0.15 m of a card counts as that card (design N6 fallback). */
    private fun nearestCard(stage: Vec3?): SourceId? {
        val p = stage ?: return null
        return ui.pinned.entries.filter { it.value.position.distanceTo(p) < 0.15f }.minByOrNull { it.value.position.distanceTo(p) }?.key
    }

    /**
     * System drag on a tray thing (hands): start = press (lift, or drag a returnable one in the return
     * stage), moves map the hand's travel onto the board, end = let go.
     */
    fun onSpatialDrag(phase: String, value: SpatialDragValue) {
        val state = ui
        val task = engine.task ?: return
        val pose = state.tray ?: return
        if (state.screen != Screen.TASK && state.screen != Screen.REDO) return
        if (state.input == InputMode.CONTROLLER && controllerSeenMs != NEVER) return
        when (phase) {
            "start" -> {
                val target = value.targetEntity ?: return
                val item = tray?.itemOf(target) ?: runCatching { target.getParent() }.getOrNull()?.let { tray?.itemOf(it) } ?: return
                val drag = item.returnable && task.stage == TrayTask.Stage.RETURN
                if (!task.press(item, drag)) return
                engine.userActed()
                dragItem = item
                dragMoved = false
                val it = task.items[item]
                dragLocal = Vec3(it?.x ?: 0f, 0f, it?.z ?: 0f)
                val dist = head.position.distanceTo(pose.center).coerceAtLeast(0.3f)
                dragPxToM = PX_M * (0.67f + 1.186f * dist)
                TmLog.i("DRAG start ${item.tag} drag=$drag")
            }
            "move" -> {
                if (dragItem == null) return
                val a = value.dragAmount
                if (abs(a.x) + abs(a.y) > 2f) dragMoved = true
                if (!task.isDragging) return
                // view right -> tray x, view up -> away from the user (tray -z)
                dragLocal = Vec3(dragLocal.x + a.x * dragPxToM, 0f, dragLocal.z - (-a.y) * dragPxToM)
                task.dragTo(dragLocal.x, dragLocal.z, if (state.screen == Screen.TASK) engine.level() else 0)
            }
            else -> {
                if (dragItem == null) return
                TmLog.i("DRAG end ${dragItem?.tag}")
                task.release()
                dragItem = null
                main.postDelayed({ dragMoved = false }, 300L)
            }
        }
    }

    private fun toStage(x: Float, y: Float, z: Float): Vec3? {
        val c = content ?: return null
        val probe = contentProbe ?: return null
        return runCatching {
                val local = c.convertPosition(Vector3(x, y, z), ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace)
                probe.convertPositionTo(local, null).domain()
            }
            .onFailure { TmLog.w("TAP convert failed: $it") }
            .getOrNull()
    }

    // =========================================================================================
    // debug probe
    // =========================================================================================

    override fun stateLine(): List<String> {
        val lines = ArrayList<String>()
        lines += "STATE ${vm.summary()} group=${TmLog.f(engine.group, 3)} plateau=${engine.plateau}"
        val gains =
            SourceId.entries.joinToString(" ") { id ->
                val g = engine.gainDb[id] ?: Engine.NEG_INF
                val gs = if (g == Engine.NEG_INF) "-inf" else TmLog.f(g, 1)
                val next = engine.nextPulseIn(id)?.let { " next=${TmLog.f(it, 1)}" } ?: ""
                val pos = ui.pinned[id]?.position?.let { " at=${TmLog.pos(it)}" } ?: ""
                "${id.tag}=$gs$next$pos"
            }
        lines += "GAINS $gains hiss=${if (engine.fridgeHissDb == Engine.NEG_INF) "-inf" else TmLog.f(engine.fridgeHissDb, 1)} clockHard=${TmLog.f(engine.clockHard)} tapBass=${TmLog.f(engine.tapBass)}"
        val o = engine.overlay
        lines += "OVERLAY hazeAlpha=${TmLog.f(o.haze, 3)} hazeAlphaMax=${TmLog.f(o.hazeMax, 3)} blobAlphaMax=${TmLog.f(o.blobMax, 3)} " +
            o.blobs.mapIndexed { i, b -> "b$i(az=${TmLog.f(b.az, 1)} el=${TmLog.f(b.el, 1)} a=${TmLog.f(b.alpha, 3)} off=${TmLog.f(OverlayModel.offAxis(b.az, b.el), 1)})" }.joinToString(" ")
        return lines
    }

    override fun setStateLines(on: Boolean): String {
        stateLines = on
        stateLine().forEach { TmLog.i(it) }
        return "STATE lines ${if (on) "on" else "off"}"
    }

    override fun panelLines(): List<String> {
        val rig = rig ?: return emptyList()
        return rig.all.filter { it.shown && it.panel != null }.map { slot ->
            String.format(Locale.US, "PANEL id=%s pos=%s %s scale=%.3f size_dp=%.0fx%.0f engine=%s", slot.id, TmLog.pos5(slot.position), TmLog.quat5(slot.rotation), slot.scale, slot.widthDp, slot.heightDp, TmLog.pos5(slot.enginePosition()))
        }
    }

    override fun poseLine(): String = "POSE hmd pos=${TmLog.pos5(head.position)} ${TmLog.quat5(head.rotation)} fwd=${TmLog.pos5(head.forward)} valid=$hasHead input=${ui.input} controller=${controllerSeenMs != NEVER}"

    override fun parkPanel(): String {
        bodyFollower.park(head, SystemClock.elapsedRealtime())
        frontPlaced = false
        return "FOLLOW body park requested"
    }

    override fun setUvTest(on: Boolean): String {
        uvTest = on
        return "UVTEST $on (card faces show F↑)"
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE
        private const val REBIND_DELAY_MS = 150L
        private const val HEAD_SAMPLE_MS = 100L
        private const val AUDIO_MS = 100L
        private const val PANEL_HOLD_MS = 600L
        private const val BLOCK_GRACE_MS = 3000L
        private const val CONTROLLER_GONE_MS = 2000L
        private const val TRIGGER_ON = 0.6f
        private const val SEE_THROUGH_M = 0.3f
        private const val FRONT_OVER_TRAY_DROP_M = 0.02f
        private const val PX_M = 0.0004f
        private const val LABEL_TEXT_M = 0.025f
        private const val LABEL_TEXT_MAX_M = 0.06f

        /** Cap height of the label's 28 sp text at panel scale 1 (1 dp = 0.8 mm). */
        private const val LABEL_TEXT_AT_SCALE1_M = 0.0224f
        private val RING_COUNT = intArrayOf(1, 1, 2, 2, 3)
        private val LABEL_SCREENS = setOf(Screen.ROOM_READY, Screen.PINNING, Screen.BASELINE, Screen.TASK, Screen.UNLOAD, Screen.REDO, Screen.ENDING, Screen.INTRO, Screen.INTRO_ARMED, Screen.QUIET)

        /** Body panel: 40 deg left, 13 deg down, 0.8 m (level head frame), leaning back 10 deg. */
        val BODY_OFFSET =
            Vec3(
                -(sin(Math.toRadians(40.0)) * cos(Math.toRadians(13.0)) * 0.8).toFloat(),
                -(sin(Math.toRadians(13.0)) * 0.8).toFloat(),
                -(cos(Math.toRadians(40.0)) * cos(Math.toRadians(13.0)) * 0.8).toFloat(),
            )

        /** Turned to face the head (40 deg) and leaning back 10 deg. */
        val BODY_LEAN: Quat4 = Quat4.axisAngle(Vec3.UP, Math.toRadians(40.0).toFloat()) * Quat4.axisAngle(Vec3.RIGHT, -Math.toRadians(10.0).toFloat())

        /** Slab test of a ray (origin, unnormalised direction) against an axis-aligned box. */
        fun rayBox(o: Vec3, d: Vec3, c: Vec3, h: Vec3): Float? {
            var t0 = -Float.MAX_VALUE
            var t1 = Float.MAX_VALUE
            val oo = floatArrayOf(o.x - c.x, o.y - c.y, o.z - c.z)
            val dd = floatArrayOf(d.x, d.y, d.z)
            val hh = floatArrayOf(h.x, h.y, h.z)
            for (i in 0..2) {
                if (abs(dd[i]) < 1e-7f) {
                    if (abs(oo[i]) > hh[i]) return null
                    continue
                }
                var a = (-hh[i] - oo[i]) / dd[i]
                var b = (hh[i] - oo[i]) / dd[i]
                if (a > b) a = b.also { b = a }
                t0 = maxOf(t0, a)
                t1 = minOf(t1, b)
                if (t0 > t1) return null
            }
            return if (t1 < 0f) null else maxOf(t0, 0f)
        }
    }
}
