package io.embodit.toomuch.ui.tm

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.embodit.toomuch.data.AnchorGateway
import io.embodit.toomuch.data.AnchorOutcome
import io.embodit.toomuch.data.HomeEntry
import io.embodit.toomuch.data.Settings
import io.embodit.toomuch.data.SettingsStore
import io.embodit.toomuch.domain.engine.Engine
import io.embodit.toomuch.domain.engine.EngineEvent
import io.embodit.toomuch.domain.engine.Mode
import io.embodit.toomuch.domain.engine.TaskEvent
import io.embodit.toomuch.domain.engine.TrayTask
import io.embodit.toomuch.domain.model.InputMode
import io.embodit.toomuch.domain.model.LevelHead
import io.embodit.toomuch.domain.model.Placement
import io.embodit.toomuch.domain.model.RoomFrame
import io.embodit.toomuch.domain.model.RoomRules
import io.embodit.toomuch.domain.model.SourceId
import io.embodit.toomuch.domain.model.Sources
import io.embodit.toomuch.domain.model.Vec3
import io.embodit.toomuch.platform.TmGraph
import io.embodit.toomuch.platform.TmLog
import java.util.Locale
import kotlin.math.atan2
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The state machine of design `states` (S01 - S16) and the owner of the [Engine]. Every input, from
 * the panels, the scene or the debug receiver, arrives as a [TmEvent] through [onEvent]; the scene
 * steps the engine once per frame through [onFrame]. Main thread only.
 */
class TmViewModel(private val store: SettingsStore, private val anchors: AnchorGateway) : ViewModel() {

    private val settings0: Settings = store.load()
    private val _state =
        MutableStateFlow(TmUiState(language = settings0.language, intensity = settings0.intensity, muted = settings0.muted, room = settings0.room))
    val state: StateFlow<TmUiState> = _state
    private val _effects = MutableSharedFlow<TmEffect>(extraBufferCapacity = 16)
    val effects: SharedFlow<TmEffect> = _effects

    val engine = Engine(seed = SystemClock.elapsedRealtime())

    private var head: LevelHead? = null
    private var headPos: Vec3? = null
    private var sceneReady = false
    private var loadStarted = false
    private var autoJob: Job? = null
    private var guideJob: Job? = null
    private var pinnedJob: Job? = null
    private var lastLogged = ""

    init {
        engine.intensity = settings0.intensity
        engine.muted = settings0.muted
        logState("init")
    }

    private val s: TmUiState
        get() = _state.value

    private fun set(block: (TmUiState) -> TmUiState) {
        _state.update(block)
        logState("")
    }

    private fun logState(reason: String) {
        val st = s
        val line = "${st.designState} screen=${st.screen.name.lowercase(Locale.US)} level=${st.level} lang=${st.language.tag}"
        if (line != lastLogged) {
            lastLogged = line
            TmLog.i("STATE $line${if (reason.isNotEmpty()) " ($reason)" else ""}")
        }
    }

    private fun emit(e: TmEffect) {
        _effects.tryEmit(e)
    }

    private fun saveSettings() {
        val st = s
        store.save(Settings(language = st.language, intensity = st.intensity, muted = st.muted, room = st.room, introSeen = true, lastTaskS = st.taskSeconds))
    }

    // =========================================================================================
    // per frame (from the scene's ECS system)
    // =========================================================================================

    /** Steps the engine and applies its discrete events. Returns them for the scene (pulses, logs). */
    fun onFrame(dt: Float, lampDir: Pair<Float, Float>?): List<EngineEvent> {
        val events = engine.step(dt, lampDir)
        for (e in events) handleEngine(e)
        return events
    }

    private fun handleEngine(e: EngineEvent) {
        when (e) {
            is EngineEvent.LevelChanged -> {
                TmLog.i("LEVEL ${e.level} t=${TmLog.f(engine.levels.t, 1)} actions=${engine.levels.actions}")
                if (s.screen == Screen.TASK) {
                    set { it.copy(level = e.level) }
                    if (e.level >= 4 && !s.plateau) enterPlateau()
                }
            }
            is EngineEvent.Pulse -> Unit
            is EngineEvent.Interrupted -> TmLog.i("INTERRUPT by=${e.source.tag} item=${e.item.tag}")
            is EngineEvent.Task -> onTask(e.event)
            EngineEvent.IdleTimeout -> if (s.screen == Screen.TASK && !s.hold) toUnload(auto = true)
            EngineEvent.HelpReady -> set { it.copy(helpReady = true) }
            EngineEvent.Silenced -> TmLog.i("GAIN silenced t_ms=${SystemClock.elapsedRealtime()} group=0 sources=-inf")
        }
    }

    private fun onTask(e: TaskEvent) {
        when (e) {
            is TaskEvent.Lifted -> TmLog.i("TASK lift ${e.item.tag} first=${e.firstTime}")
            is TaskEvent.Revealed -> TmLog.i("TASK keys under ${e.under.tag}")
            TaskEvent.KeyHung -> {
                TmLog.i("TASK keys on the hook")
                set { it.copy(taskStage = TaskStage.RETURN) }
            }
            is TaskEvent.Snapped -> {
                TmLog.i("TASK snapped ${e.item.tag}")
                emit(TmEffect.Snap)
            }
            TaskEvent.AllReturned -> {
                TmLog.i("TASK all back")
                when (s.screen) {
                    Screen.TASK -> toUnload(auto = false)
                    Screen.REDO -> toEnding(quiet = engine.quietS)
                    else -> Unit
                }
            }
            is TaskEvent.Dropped -> TmLog.i("TASK dropped ${e.item.tag}")
        }
    }

    private fun enterPlateau() {
        if (s.plateauShown) {
            set { it.copy(plateau = true) }
            return
        }
        set { it.copy(plateau = true, plateauShown = true, guideOverride = GuideOverride.PLATEAU) }
        guideJob?.cancel()
        guideJob =
            viewModelScope.launch {
                delay(PLATEAU_LINE_MS)
                if (!s.hold && s.guideOverride == GuideOverride.PLATEAU) set { it.copy(guideOverride = null) }
            }
    }

    // =========================================================================================
    // events
    // =========================================================================================

    fun onEvent(e: TmEvent) {
        when (e) {
            TmEvent.SceneReady -> {
                sceneReady = true
                startLoading()
            }
            is TmEvent.HeadSample -> onHead(e.position, e.forward)
            is TmEvent.InputDetected -> if (!s.inputForced && s.input != e.mode) set { it.copy(input = e.mode) }
            is TmEvent.Blocked -> if (e.reason != null) set { it.copy(block = e.reason, screen = Screen.UNAVAILABLE) }
            is TmEvent.PinAt -> onPinAt(e.point, s.current, null)
            is TmEvent.CardTapped -> onCardTapped(e.source)
            TmEvent.EmptyTapped -> onEmpty()
            is TmEvent.CardYaw -> {
                val p = s.pinned[e.source] ?: return
                set { it.copy(pinned = it.pinned + (e.source to p.copy(yaw = e.yaw))) }
                if (e.settled) p.anchorId?.let { store.saveHome(e.source, HomeEntry(it, e.yaw)) }
            }
            TmEvent.QuietNow -> onQuietNow()
            is TmEvent.MuteSet -> {
                engine.muted = e.muted
                set { it.copy(muted = e.muted) }
                saveSettings()
                TmLog.i("MUTE ${e.muted}")
            }
            TmEvent.MenuToggle -> setMenu(!s.menuOpen)
            is TmEvent.CapsuleSource -> onCapsule(e.source)
            TmEvent.AutoPlace -> autoPlaceAndGo()
            TmEvent.Start -> onStart()
            TmEvent.Nearer -> moveAdjusting(-1)
            TmEvent.Farther -> moveAdjusting(+1)
            TmEvent.DefaultSpot -> s.adjusting?.let { id ->
                placeAtDefault(id)
                set { it.copy(adjusting = null) }
            }
            TmEvent.DeletePin -> s.adjusting?.let { id -> deletePin(id) }
            TmEvent.AdjustDone -> set { it.copy(adjusting = null) }
            TmEvent.SkipToUnload -> if (s.screen == Screen.TASK) toUnload(auto = false)
            TmEvent.DoneUnloading -> if (s.screen == Screen.UNLOAD && s.unloadedCount >= 3) toRedo()
            TmEvent.DoRest -> if (s.screen == Screen.UNLOAD && s.helpReady) {
                for (spec in Sources.all) if (spec.id !in s.unloaded) chooseUnload(spec.id, 0, sound = false)
                toRedo()
            }
            TmEvent.SkipRedo -> if (s.screen == Screen.REDO) toEnding(quiet = null)
            TmEvent.Continue -> onContinue()
            TmEvent.GoUnload -> if (s.screen == Screen.QUIET && s.returnTo == Screen.TASK) toUnload(auto = false)
            TmEvent.Exit -> {
                saveSettings()
                emit(TmEffect.ExitApp)
            }
            TmEvent.Again -> if (s.screen == Screen.ENDING) {
                engine.enter(Mode.SILENT)
                engine.clearTask()
                engine.clearUnloads()
                set { it.copy(screen = Screen.INTRO, tray = null, unloaded = emptyMap(), plateau = false, plateauShown = false, level = 0, quietSeconds = null, guideOverride = null, taskStage = TaskStage.KEYS, cardFor = null) }
            }
            TmEvent.About -> {
                val from = s.screen
                set { it.copy(screen = Screen.ABOUT, aboutFrom = from, menuOpen = false) }
                engine.paused = true
            }
            TmEvent.AboutOk -> if (s.screen == Screen.ABOUT) {
                val back = s.aboutFrom ?: Screen.ENDING
                set { it.copy(screen = back, aboutFrom = null) }
                engine.paused = false
            }
            is TmEvent.IntensitySet -> {
                engine.intensity = e.intensity
                set { it.copy(intensity = e.intensity) }
                saveSettings()
            }
            TmEvent.ConfirmClear -> clearHome()
            TmEvent.CancelClear -> set { it.copy(confirmClear = false) }
            is TmEvent.UnloadChoose -> if (s.screen == Screen.UNLOAD) chooseUnload(e.source, e.option, sound = true)
            TmEvent.CloseCard -> set { it.copy(cardFor = null, cardDone = false) }
            is TmEvent.LanguageSet -> {
                set { it.copy(language = e.language) }
                saveSettings()
            }
            is TmEvent.MenuPageSet -> set { it.copy(menuPage = e.page) }
            is TmEvent.RoomStep -> {
                val r = s.room
                val n =
                    when (e.what) {
                        'l' -> r.stepLength(e.dir)
                        'w' -> r.stepWidth(e.dir)
                        'h' -> r.stepHeight(e.dir)
                        else -> r.stepFloor(e.dir)
                    }
                set { it.copy(room = n) }
                saveSettings()
            }
            TmEvent.ResetAroundMe -> {
                val h = head ?: return
                val p = headPos ?: return
                set { it.copy(roomFrame = RoomFrame(p.x, p.z, h.yaw, p.y), tray = if (it.tray != null) Placement.tray(h) else null, traySerial = it.traySerial + 1, menuOpen = false) }
                engine.paused = false
                // the phone's default spot belongs to the tray: one that sat there, or would now be inside the tray, goes along
                val phone = s.pinned[SourceId.PHONE]
                val tray = s.tray
                if (phone != null && (phone.snapped || (tray != null && tray.toLocal(phone.position).let { l -> kotlin.math.abs(l.x) < 0.5f && kotlin.math.abs(l.z) < 0.35f && l.y < 0.3f }))) placeAtDefault(SourceId.PHONE)
                TmLog.i("RESET around head=${TmLog.pos(p)} yaw=${TmLog.f(Math.toDegrees(h.yaw.toDouble()).toFloat(), 1)}")
            }
            TmEvent.Repin -> if (!s.homeLocked) {
                engine.enter(Mode.PINNING)
                set { it.copy(screen = Screen.PINNING, menuOpen = false, current = SourceId.FRIDGE, adjusting = null) }
                engine.paused = false
            }
            TmEvent.ClearHome -> if (!s.homeLocked) set { it.copy(confirmClear = true, menuOpen = false) }.also { engine.paused = false }
            TmEvent.WentBackground -> {
                engine.paused = true
                TmLog.i("APP background")
            }
            TmEvent.CameForeground -> {
                TmLog.i("APP foreground")
                engine.paused = s.menuOpen || s.screen == Screen.ABOUT
                if (s.screen in setOf(Screen.PINNING, Screen.BASELINE, Screen.TASK, Screen.UNLOAD, Screen.REDO)) toQuiet()
            }
            is TmEvent.DebugGoto -> debugGoto(e.state)
            is TmEvent.DebugPin -> {
                if (s.screen != Screen.PINNING) return
                val frame = s.roomFrame ?: return
                val stage = frame.toStage(Vec3(e.local.x, s.room.floorOffset + e.local.y, e.local.z))
                set { it.copy(current = e.source, adjusting = null) }
                onPinAt(stage, e.source, e.yawDeg?.let { Math.toRadians(it.toDouble()).toFloat() + frame.yaw })
            }
            is TmEvent.DebugLevel -> {
                engine.levels.lock(e.n)
                if (s.screen == Screen.TASK) {
                    set { it.copy(level = e.n) }
                    if (e.n >= 4) enterPlateau() else set { it.copy(plateau = false) }
                }
            }
            is TmEvent.DebugOverlay -> engine.overlayLock = e.n.takeIf { it >= 0 }
            is TmEvent.DebugInput -> set { it.copy(input = e.mode ?: it.input, inputForced = e.mode != null) }
            is TmEvent.DebugRoom -> {
                set { it.copy(room = e.size) }
                saveSettings()
            }
            is TmEvent.DebugSeed -> {
                set { it.copy(seed = e.seed.coerceIn(1, 4)) }
                if (s.screen == Screen.BASELINE || (s.screen == Screen.TASK && engine.task?.flippedCount == 0)) {
                    engine.newTask(s.seed, quiet = false)
                    set { it.copy(traySerial = it.traySerial + 1) }
                }
            }
            is TmEvent.DebugElapsed -> {
                engine.setElapsed(e.task.toFloat(), e.quiet?.toFloat())
                set { it.copy(taskSeconds = e.task, quietSeconds = e.quiet) }
            }
            is TmEvent.DebugInterrupt -> {
                val out = ArrayList<EngineEvent>()
                engine.interruptBy(e.source, out, forced = true)
                out.forEach(::handleEngine)
                TmLog.i("INTERRUPT forced by=${e.source.tag} dropped=${out.any { it is EngineEvent.Interrupted }}")
            }
            is TmEvent.DebugHold -> set { it.copy(hold = e.hold) }
            is TmEvent.DebugInstant -> engine.instant = e.instant
            is TmEvent.DebugTimescale -> engine.timescale = e.k.coerceIn(0.05f, 50f)
            is TmEvent.DebugAudio -> set { it.copy(audio = e.backend) }
            TmEvent.DebugResetHome -> clearHome()
        }
    }

    // ---- start-up: anchors, the room, the intro

    private fun startLoading() {
        if (loadStarted) return
        loadStarted = true
        viewModelScope.launch {
            val records = loadAnchors()
            val home = store.home()
            val pinned = LinkedHashMap<SourceId, Pinned>()
            for (id in SourceId.entries) {
                val entry = home[id]
                val rec = records.firstOrNull { it.id == entry?.anchorId } ?: records.firstOrNull { it.name == anchorName(id) }
                // the anchor was created with no rotation in the Stage of its launch: its rotation now is that
                // Stage's turn into this one (the Stage re-centres on the head at every launch)
                if (rec != null) {
                    val turn = rec.pose.rotation.rotate(Vec3(0f, 0f, 1f)).let { atan2(it.x, it.z) }
                    pinned[id] = Pinned(rec.pose.position, (entry?.yaw ?: 0f) + turn, rec.id, snapped = false)
                    TmLog.i("ANCHOR ${id.tag} restored at=${TmLog.pos(rec.pose.position)} turn=${TmLog.f(Math.toDegrees(turn.toDouble()).toFloat(), 1)}")
                }
            }
            TmLog.i("ANCHOR loaded records=${records.size} home=${home.size} pinned=${pinned.keys.joinToString(",") { it.tag }}")
            set { it.copy(pinned = pinned, anchorsLoaded = true) }
            maybeRoomReady()
        }
        viewModelScope.launch {
            // the anchors may not answer: the start never waits longer than 5 s for them
            delay(5_000L)
            if (!s.anchorsLoaded) {
                TmLog.w("ANCHOR load slow: going on without")
                set { it.copy(anchorsLoaded = true) }
                maybeRoomReady()
            }
        }
    }

    private suspend fun loadAnchors(): List<io.embodit.toomuch.data.AnchorRecord> {
        repeat(3) { attempt ->
            when (val r = anchors.loadAll()) {
                is AnchorOutcome.Ok -> return r.value
                is AnchorOutcome.Failed -> {
                    TmLog.w("ANCHOR load failed code=${r.code} msg=${r.message} attempt=${attempt + 1}")
                    if (r.code != -5 && r.code != -6 && r.code != -100) return emptyList()
                    delay(1_000L)
                }
            }
        }
        return emptyList()
    }

    private fun onHead(position: Vec3, forward: Vec3) {
        headPos = position
        head = LevelHead.of(position, forward)
        if (s.roomFrame == null) maybeRoomReady()
    }

    private fun maybeRoomReady() {
        if (s.screen != Screen.STARTING || !sceneReady || !s.anchorsLoaded) return
        val p = headPos ?: return
        val f = head ?: return
        set { it.copy(screen = Screen.ROOM_READY, roomFrame = RoomFrame(p.x, p.z, f.yaw, p.y)) }
        TmLog.i("ROOM frame center=(${TmLog.f(p.x)}, ${TmLog.f(p.z)}) yaw=${TmLog.f(Math.toDegrees(f.yaw.toDouble()).toFloat(), 1)} head_y=${TmLog.f(p.y)}")
        autoJob?.cancel()
        autoJob =
            viewModelScope.launch {
                delay(ROOM_READY_MS)
                while (s.hold) delay(200L)
                if (s.screen == Screen.ROOM_READY) set { it.copy(screen = Screen.INTRO) }
            }
    }

    // ---- quiet

    private fun onQuietNow() {
        TmLog.i("GAIN quiet pressed t_ms=${SystemClock.elapsedRealtime()} screen=${s.designState} group=${TmLog.f(engine.group, 3)}")
        when (s.screen) {
            Screen.INTRO -> set { it.copy(screen = Screen.INTRO_ARMED) }
            Screen.INTRO_ARMED, Screen.STARTING, Screen.UNAVAILABLE, Screen.QUIET -> Unit
            else -> toQuiet()
        }
    }

    private fun toQuiet() {
        val from = s.screen
        if (from == Screen.QUIET) return
        engine.enter(Mode.QUIET)
        set { it.copy(screen = Screen.QUIET, returnTo = from, returnStage = it.taskStage, returnLevel = engine.level(), menuOpen = false, cardFor = null, adjusting = null, plateau = false) }
        engine.paused = false
    }

    private fun onContinue() {
        when (s.screen) {
            Screen.INTRO_ARMED -> {
                val all = s.pinned.size == SourceId.entries.size
                if (all) toBaseline(reused = true) else toPinning()
            }
            Screen.QUIET -> {
                when (val back = s.returnTo ?: Screen.BASELINE) {
                    Screen.TASK -> {
                        engine.resumeTask(s.returnLevel)
                        engine.enter(Mode.TASK)
                        set { it.copy(screen = Screen.TASK, level = engine.level(), returnTo = null) }
                    }
                    Screen.PINNING -> {
                        engine.enter(Mode.PINNING)
                        set { it.copy(screen = Screen.PINNING, returnTo = null) }
                    }
                    Screen.UNLOAD -> {
                        engine.enter(Mode.UNLOAD)
                        set { it.copy(screen = Screen.UNLOAD, returnTo = null) }
                    }
                    Screen.REDO -> {
                        engine.enter(Mode.REDO)
                        set { it.copy(screen = Screen.REDO, returnTo = null) }
                    }
                    Screen.ENDING -> {
                        engine.enter(Mode.ENDING)
                        set { it.copy(screen = Screen.ENDING, returnTo = null) }
                    }
                    else -> {
                        engine.enter(if (back == Screen.BASELINE) Mode.BASELINE else Mode.SILENT)
                        set { it.copy(screen = back, returnTo = null) }
                    }
                }
            }
            else -> Unit
        }
    }

    // ---- pinning

    private fun firstUnpinned(pinned: Map<SourceId, Pinned>): SourceId? = SourceId.entries.firstOrNull { it !in pinned }

    private fun toPinning() {
        engine.enter(Mode.PINNING)
        set { it.copy(screen = Screen.PINNING, current = firstUnpinned(it.pinned) ?: SourceId.FRIDGE, adjusting = null, justPinned = null, reusedHome = false) }
    }

    private fun onCapsule(id: SourceId) {
        when (s.screen) {
            Screen.PINNING -> if (id in s.pinned) set { it.copy(adjusting = id) } else set { it.copy(current = id, adjusting = null) }
            Screen.UNLOAD -> openCard(id)
            else -> Unit
        }
    }

    private fun onCardTapped(id: SourceId) {
        when (s.screen) {
            Screen.PINNING -> set { it.copy(adjusting = if (it.adjusting == id) null else id) }
            Screen.UNLOAD -> openCard(id)
            else -> TmLog.i("TAP card ${id.tag} ignored in ${s.designState}")
        }
    }

    private fun onEmpty() {
        when {
            s.menuOpen -> setMenu(false)
            s.screen == Screen.PINNING && s.adjusting != null -> set { it.copy(adjusting = null) }
            s.screen == Screen.UNLOAD && s.cardFor != null -> set { it.copy(cardFor = null, cardDone = false) }
            else -> Unit
        }
    }

    private fun yawFacing(point: Vec3): Float {
        val p = headPos ?: return 0f
        return atan2(p.x - point.x, p.z - point.z)
    }

    /** A pin for [id] at a Stage [point]: clearance, snap to its own frame, anchor (async), preview. */
    private fun onPinAt(point: Vec3, id: SourceId?, yawOverride: Float?) {
        if (s.screen != Screen.PINNING) return
        if (s.adjusting != null) {
            // tapping the room while adjusting = "done"
            set { it.copy(adjusting = null) }
            return
        }
        val source = id ?: return
        val frame = s.roomFrame ?: return
        val h = headPos ?: return
        val spot = RoomRules.spots(s.room, frame).firstOrNull { it.source == source }
        val (p, snapped) = RoomRules.resolvePin(point, h, spot, frame)
        val yaw = yawOverride ?: if (snapped && spot != null && spot.normal.y == 0f) spotYaw(frame, spot.normal) else yawFacing(p)
        TmLog.i("PIN ${source.tag} raw=${TmLog.pos(point)} at=${TmLog.pos(p)} snapped=$snapped")
        if (snapped) emit(TmEffect.Snap)
        putPin(source, p, yaw, snapped)
        engine.preview(source)
        val next = firstUnpinned(s.pinned)
        val hands = s.input == InputMode.HANDS
        set { it.copy(justPinned = source, current = next ?: it.current, adjusting = if (hands) source else null) }
        pinnedJob?.cancel()
        pinnedJob =
            viewModelScope.launch {
                delay(JUST_PINNED_MS)
                while (s.hold) delay(200L)
                set { it.copy(justPinned = null) }
            }
    }

    private fun spotYaw(frame: RoomFrame, localNormal: Vec3): Float {
        val n = frame.dirToStage(localNormal)
        return atan2(n.x, n.z)
    }

    /** Records the pin and (re)creates its anchor; a failed anchor keeps the card (log ANCHOR fallback). */
    private fun putPin(id: SourceId, position: Vec3, yaw: Float, snapped: Boolean) {
        val old = s.pinned[id]
        set { it.copy(pinned = it.pinned + (id to Pinned(position, yaw, null, snapped))) }
        viewModelScope.launch {
            old?.anchorId?.let { anchors.remove(it) }
            when (val r = anchors.create(position, anchorName(id))) {
                is AnchorOutcome.Ok -> {
                    val cur = s.pinned[id]
                    if (cur != null && cur.position == position) {
                        set { it.copy(pinned = it.pinned + (id to cur.copy(anchorId = r.value.id))) }
                        store.saveHome(id, HomeEntry(r.value.id, cur.yaw))
                    } else {
                        anchors.remove(r.value.id)
                    }
                    TmLog.i("ANCHOR ok ${id.tag} id=${r.value.id}")
                }
                is AnchorOutcome.Failed -> {
                    store.saveHome(id, null)
                    TmLog.w("ANCHOR fallback ${id.tag} code=${r.code} msg=${r.message}")
                }
            }
        }
    }

    private fun placeAtDefault(id: SourceId) {
        val frame = s.roomFrame ?: return
        val spot = RoomRules.spots(s.room, frame).firstOrNull { it.source == id } ?: return
        val p = frame.toStage(spot.point)
        val yaw = if (spot.normal.y == 0f) spotYaw(frame, spot.normal) else yawFacing(p)
        putPin(id, p, yaw, snapped = true)
        TmLog.i("PIN ${id.tag} default at=${TmLog.pos(p)}")
    }

    private fun moveAdjusting(dir: Int) {
        val id = s.adjusting ?: return
        val p = s.pinned[id] ?: return
        val h = headPos ?: return
        val frame = s.roomFrame ?: return
        val moved = RoomRules.nearerFarther(p.position, h, dir)
        val spot = RoomRules.spots(s.room, frame).firstOrNull { it.source == id }
        val (q, snapped) = RoomRules.resolvePin(moved, h, spot, frame)
        if (snapped) emit(TmEffect.Snap)
        putPin(id, q, p.yaw, snapped)
        TmLog.i("PIN ${id.tag} ${if (dir < 0) "nearer" else "farther"} at=${TmLog.pos(q)} snapped=$snapped")
    }

    private fun deletePin(id: SourceId) {
        val p = s.pinned[id]
        set { it.copy(pinned = it.pinned - id, adjusting = null, current = id) }
        store.saveHome(id, null)
        p?.anchorId?.let { a -> viewModelScope.launch { anchors.remove(a) } }
        TmLog.i("PIN ${id.tag} deleted")
    }

    private fun autoPlaceAndGo() {
        if (s.screen != Screen.PINNING) return
        autoPlace()
        toBaseline(reused = false)
    }

    private fun autoPlace() {
        for (id in SourceId.entries) if (id !in s.pinned) placeAtDefault(id)
    }

    private fun onStart() {
        when (s.screen) {
            Screen.PINNING -> if (s.pinned.size >= 3) {
                autoPlace()
                toBaseline(reused = false)
            }
            Screen.BASELINE -> toTask()
            else -> Unit
        }
    }

    // ---- baseline, task, unload, redo, ending

    private fun toBaseline(reused: Boolean) {
        val h = head
        engine.newTask(s.seed, quiet = false)
        engine.enter(Mode.BASELINE)
        set { it.copy(screen = Screen.BASELINE, reusedHome = reused, adjusting = null, justPinned = null, tray = h?.let { hh -> Placement.tray(hh) } ?: it.tray, traySerial = it.traySerial + 1, level = 0) }
    }

    private fun toTask() {
        engine.startRun()
        engine.newTask(s.seed, quiet = false)
        engine.enter(Mode.TASK)
        set { it.copy(screen = Screen.TASK, taskStage = TaskStage.KEYS, plateau = false, plateauShown = false, level = 0, unloaded = emptyMap(), traySerial = it.traySerial + 1, guideOverride = null) }
    }

    private fun toUnload(auto: Boolean) {
        engine.overlayLock = null
        engine.levels.unlock()
        engine.enter(Mode.UNLOAD)
        set { it.copy(screen = Screen.UNLOAD, level = 2, plateau = false, cardFor = null, cardDone = false, helpReady = false, taskSeconds = engine.elapsedS.toInt(), guideOverride = if (auto) GuideOverride.AUTO_UNLOAD else null, returnTo = null) }
        if (auto) {
            guideJob?.cancel()
            guideJob =
                viewModelScope.launch {
                    delay(PLATEAU_LINE_MS)
                    if (!s.hold && s.guideOverride == GuideOverride.AUTO_UNLOAD) set { it.copy(guideOverride = null) }
                }
        }
    }

    private fun openCard(id: SourceId) {
        val h = head ?: return
        val p = s.pinned[id] ?: return
        val tray = s.tray
        val box =
            tray?.corners()?.let { cs ->
                Vec3(cs.minOf { it.x }, cs.minOf { it.y }, cs.minOf { it.z }) to Vec3(cs.maxOf { it.x }, cs.maxOf { it.y } + 0.05f, cs.maxOf { it.z })
            }
        val (pos, front) = Placement.unloadCard(h, p.position, box)
        set { it.copy(cardFor = id, cardDone = id in it.unloaded, cardPos = pos, cardFront = front) }
        TmLog.i("CARD ${id.tag} at=${TmLog.pos(pos)} front=$front")
    }

    private fun chooseUnload(id: SourceId, option: Int, sound: Boolean) {
        val spec = Sources[id]
        val effect = spec.options.getOrNull(option) ?: return
        engine.unload(id, effect)
        set { it.copy(unloaded = it.unloaded + (id to option), cardDone = it.cardFor == id) }
        if (sound) emit(TmEffect.Ding)
        TmLog.i("UNLOAD ${id.tag} opt=$option effect=${effect.name.lowercase(Locale.US)}")
    }

    private fun toRedo() {
        val seed = s.seed % 4 + 1
        engine.newTask(seed, quiet = true)
        engine.enter(Mode.REDO)
        set { it.copy(screen = Screen.REDO, cardFor = null, taskStage = TaskStage.KEYS, traySerial = it.traySerial + 1, level = 1) }
        TmLog.i("REDO seed=$seed")
    }

    private fun toEnding(quiet: Float?) {
        engine.enter(Mode.ENDING)
        val task = engine.elapsedS.toInt().coerceAtLeast(s.taskSeconds)
        set { it.copy(screen = Screen.ENDING, taskSeconds = task, quietSeconds = quiet?.toInt(), cardFor = null) }
        saveSettings()
    }

    // ---- menu, home

    private fun setMenu(open: Boolean) {
        engine.paused = open
        set { it.copy(menuOpen = open, menuPage = MenuPage.MAIN) }
    }

    private fun clearHome() {
        val ids = s.pinned.values.mapNotNull { it.anchorId }
        viewModelScope.launch {
            for (a in ids) anchors.remove(a)
            // and anything left under our names from earlier runs
            (anchors.loadAll() as? AnchorOutcome.Ok)?.value?.filter { it.name.startsWith(ANCHOR_PREFIX) }?.forEach { anchors.remove(it.id) }
        }
        store.clearHome()
        engine.enter(Mode.PINNING)
        set { it.copy(pinned = emptyMap(), confirmClear = false, screen = Screen.PINNING, current = SourceId.FRIDGE, adjusting = null, menuOpen = false) }
        engine.paused = false
        TmLog.i("HOME cleared anchors=${ids.size}")
    }

    // ---- debug goto (design qaHooks.sceneTable)

    private fun debugGoto(id: String) {
        engine.levels.unlock()
        engine.overlayLock = null
        set { it.copy(menuOpen = false, confirmClear = false, cardFor = null, adjusting = null, justPinned = null) }
        when (id.uppercase(Locale.US)) {
            "S01" -> set { it.copy(screen = Screen.STARTING) }.also { engine.enter(Mode.SILENT) }
            "S02" -> set { it.copy(screen = Screen.ROOM_READY, hold = true) }.also { engine.enter(Mode.SILENT) }
            "S03" -> set { it.copy(screen = Screen.INTRO) }.also { engine.enter(Mode.SILENT) }
            "S03B" -> set { it.copy(screen = Screen.INTRO_ARMED) }.also { engine.enter(Mode.SILENT) }
            "S04" -> toPinning()
            "S04B" -> {
                toPinning()
                val c = s.current ?: SourceId.FRIDGE
                val frame = s.roomFrame
                val spot = frame?.let { f -> RoomRules.spots(s.room, f).first { it.source == c } }
                if (frame != null && spot != null) onPinAt(frame.toStage(spot.point + Vec3(0.15f, 0f, 0.1f)), c, null)
                set { it.copy(adjusting = null, justPinned = c) }
            }
            "S04C" -> {
                toPinning()
                if (SourceId.FRIDGE !in s.pinned) placeAtDefault(SourceId.FRIDGE)
                set { it.copy(adjusting = SourceId.FRIDGE) }
            }
            "S05" -> {
                toPinning()
                autoPlace()
                toBaseline(reused = false)
            }
            "S06", "S07", "S08", "S09" -> {
                if (s.screen == Screen.PINNING || s.screen != Screen.TASK) {
                    toPinning()
                    autoPlace()
                    toBaseline(reused = false)
                }
                toTask()
                when (id.uppercase(Locale.US)) {
                    "S07" -> {
                        engine.task?.skipToReturn()
                        set { it.copy(taskStage = TaskStage.RETURN) }
                    }
                    "S08", "S09" -> onEvent(TmEvent.DebugLevel(4))
                }
                if (id.uppercase(Locale.US) == "S09") toQuiet()
            }
            "S10", "S10B" -> {
                if (s.screen !in setOf(Screen.TASK, Screen.UNLOAD, Screen.QUIET)) {
                    toPinning()
                    autoPlace()
                    toBaseline(reused = false)
                    engine.startRun()
                }
                toUnload(auto = false)
                if (id.uppercase(Locale.US) == "S10B") openCard(SourceId.PHONE)
            }
            "S11" -> {
                if (s.screen != Screen.UNLOAD) {
                    debugGoto("S10")
                }
                toRedo()
            }
            "S12" -> {
                if (s.tray == null) {
                    toPinning()
                    autoPlace()
                }
                toEnding(quiet = s.quietSeconds?.toFloat() ?: engine.quietS.takeIf { it > 0f })
            }
            "S13" -> setMenu(true)
            "S14" -> set { it.copy(screen = Screen.UNAVAILABLE) }.also { engine.enter(Mode.SILENT) }
            "S15" -> onEvent(TmEvent.About)
            "S16" -> onEvent(TmEvent.LanguageSet(io.embodit.toomuch.domain.model.LanguagePref.EN))
            else -> TmLog.w("CMD goto unknown state $id")
        }
        logState("goto $id")
    }

    /** One line for `state` (the scene adds positions and the per-frame values). */
    fun summary(): String {
        val st = s
        return "state=${st.designState} level=${engine.level()} t=${TmLog.f(engine.levels.t, 1)} elapsed=${TmLog.f(engine.elapsedS, 1)} quiet=${TmLog.f(engine.quietS, 1)} input=${st.input.name.lowercase(Locale.US)} intensity=${st.intensity.tag} muted=${st.muted} anchors=${st.pinned.values.count { it.anchorId != null }}/${st.pinned.size} audio=${st.audio} task=${engine.task?.let { "${it.stage.name.lowercase(Locale.US)} flipped=${it.flippedCount} keys=${it.keyRevealed} back=${it.returnedCount}" } ?: "none"}"
    }

    val keyLevel: Int
        get() = TrayTask.KEY_LEVEL

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = TmViewModel(TmGraph.settings(context), TmGraph.anchors) as T
    }

    companion object {
        const val ANCHOR_PREFIX = "toomuch-"
        const val ROOM_READY_MS = 800L
        const val JUST_PINNED_MS = 1500L
        const val PLATEAU_LINE_MS = 8000L

        fun anchorName(id: SourceId) = "$ANCHOR_PREFIX${id.tag}"
    }
}
