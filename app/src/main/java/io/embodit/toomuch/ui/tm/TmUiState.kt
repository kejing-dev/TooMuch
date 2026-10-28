package io.embodit.toomuch.ui.tm

import io.embodit.toomuch.domain.model.BlockReason
import io.embodit.toomuch.domain.model.InputMode
import io.embodit.toomuch.domain.model.Intensity
import io.embodit.toomuch.domain.model.LanguagePref
import io.embodit.toomuch.domain.model.Placement
import io.embodit.toomuch.domain.model.RoomFrame
import io.embodit.toomuch.domain.model.RoomSize
import io.embodit.toomuch.domain.model.SourceId
import io.embodit.toomuch.domain.model.Vec3

/** The main screens of design `states` (sub-states are flags on [TmUiState]). */
enum class Screen {
    STARTING, // S01
    ROOM_READY, // S02
    INTRO, // S03
    INTRO_ARMED, // S03b
    PINNING, // S04 / S04b / S04c
    BASELINE, // S05
    TASK, // S06 / S07 / S08
    QUIET, // S09
    UNLOAD, // S10 / S10b
    REDO, // S11
    ENDING, // S12
    UNAVAILABLE, // S14
    ABOUT, // S15
}

enum class TaskStage { KEYS, RETURN }

enum class MenuPage { MAIN, ROOM }

/** Layouts of the one front card (design `panels.frontCard.layouts`). */
enum class FrontLayout { STARTING, INTRO, QUIET, ENDING, ABOUT, UNAVAILABLE, CONFIRM_CLEAR }

/** A pinned source: where its card is (Stage), its yaw about the vertical, its anchor (null while creating / failed). */
data class Pinned(val position: Vec3, val yaw: Float, val anchorId: String?, val snapped: Boolean)

/** One-off guide text that replaces the state's line for a while (plateau line 8 s, "Let's stop here"). */
enum class GuideOverride { PLATEAU, AUTO_UNLOAD, QUIET_DONE }

data class TmUiState(
    val screen: Screen = Screen.STARTING,
    val language: LanguagePref = LanguagePref.SYSTEM,
    val intensity: Intensity = Intensity.GENTLE,
    val muted: Boolean = false,
    val input: InputMode = InputMode.HANDS,
    val inputForced: Boolean = false,
    val room: RoomSize = RoomSize(),
    val roomFrame: RoomFrame? = null,
    val pinned: Map<SourceId, Pinned> = emptyMap(),
    /** Which source the next pin goes to (S04). */
    val current: SourceId? = SourceId.FRIDGE,
    /** S04c: the card being adjusted. */
    val adjusting: SourceId? = null,
    /** S04b: the source just pinned (cleared after 1.5 s in controller mode). */
    val justPinned: SourceId? = null,
    val anchorsLoaded: Boolean = false,
    /** True when the last S03b continue found six stored spots (guide "using last time's spots"). */
    val reusedHome: Boolean = false,
    // task
    val level: Int = 0,
    val taskStage: TaskStage = TaskStage.KEYS,
    val plateau: Boolean = false,
    val seed: Int = 1,
    val tray: Placement.TrayPose? = null,
    /** Bumped every time the tray is (re)built: new task or "Reset around me". */
    val traySerial: Int = 0,
    val guideOverride: GuideOverride? = null,
    val plateauShown: Boolean = false,
    // quiet
    val returnTo: Screen? = null,
    val returnStage: TaskStage = TaskStage.KEYS,
    val returnLevel: Int = 0,
    // unload
    val unloaded: Map<SourceId, Int> = emptyMap(),
    val cardFor: SourceId? = null,
    val cardDone: Boolean = false,
    val cardPos: Vec3? = null,
    val cardFront: Boolean = false,
    val helpReady: Boolean = false,
    // ending
    val taskSeconds: Int = 0,
    val quietSeconds: Int? = null,
    // menu, front card
    val menuOpen: Boolean = false,
    val menuPage: MenuPage = MenuPage.MAIN,
    val confirmClear: Boolean = false,
    val aboutFrom: Screen? = null,
    val block: BlockReason? = null,
    /** Debug `hold`: freezes automatic transitions and the 8 s line swap. */
    val hold: Boolean = false,
    /** Sound back-end name for the state line. */
    val audio: String = "object",
) {
    /** The design state id (S01 .. S15; menu S13 on top of anything). */
    val designState: String
        get() =
            if (menuOpen) "S13"
            else
                when (screen) {
                    Screen.STARTING -> "S01"
                    Screen.ROOM_READY -> "S02"
                    Screen.INTRO -> "S03"
                    Screen.INTRO_ARMED -> "S03b"
                    Screen.PINNING -> if (adjusting != null) "S04c" else if (justPinned != null) "S04b" else "S04"
                    Screen.BASELINE -> "S05"
                    Screen.TASK -> if (plateau) "S08" else if (taskStage == TaskStage.KEYS) "S06" else "S07"
                    Screen.QUIET -> "S09"
                    Screen.UNLOAD -> if (cardFor != null) "S10b" else "S10"
                    Screen.REDO -> "S11"
                    Screen.ENDING -> "S12"
                    Screen.UNAVAILABLE -> "S14"
                    Screen.ABOUT -> "S15"
                }

    val front: FrontLayout?
        get() =
            when {
                confirmClear -> FrontLayout.CONFIRM_CLEAR
                screen == Screen.STARTING -> FrontLayout.STARTING
                screen == Screen.INTRO || screen == Screen.INTRO_ARMED -> FrontLayout.INTRO
                screen == Screen.QUIET -> FrontLayout.QUIET
                screen == Screen.ENDING -> FrontLayout.ENDING
                screen == Screen.ABOUT -> FrontLayout.ABOUT
                screen == Screen.UNAVAILABLE -> FrontLayout.UNAVAILABLE
                else -> null
            }

    val pinnedCount: Int
        get() = pinned.size

    /** "Quiet now" is greyed out while already quiet. */
    val quietEnabled: Boolean
        get() = screen != Screen.QUIET && screen != Screen.STARTING && screen != Screen.UNAVAILABLE

    /** Re-pin and forget-home are only allowed outside the task (design S13). */
    val homeLocked: Boolean
        get() = screen !in setOf(Screen.PINNING, Screen.BASELINE, Screen.ENDING, Screen.INTRO, Screen.INTRO_ARMED, Screen.ROOM_READY)

    val roomLinesShown: Boolean
        get() = screen == Screen.PINNING || screen == Screen.ROOM_READY || (menuOpen && menuPage == MenuPage.ROOM)

    val trayShown: Boolean
        get() = tray != null && screen in setOf(Screen.BASELINE, Screen.TASK, Screen.UNLOAD, Screen.REDO) || (tray != null && screen == Screen.QUIET && returnTo in setOf(Screen.BASELINE, Screen.TASK, Screen.UNLOAD, Screen.REDO))

    /** Sources that may be heard / shown with ripples at all (pinned ones). */
    val unloadedCount: Int
        get() = unloaded.size
}

/** Everything that can happen to the screen; the debug receiver sends the same events (no shortcuts). */
sealed interface TmEvent {
    // scene
    data object SceneReady : TmEvent

    data class HeadSample(val position: Vec3, val forward: Vec3) : TmEvent

    data class InputDetected(val mode: InputMode) : TmEvent

    data class Blocked(val reason: BlockReason?) : TmEvent

    /** A pin landed at a Stage point (controller trigger on the ghost, or a pinch on a room face). */
    data class PinAt(val point: Vec3, val fromController: Boolean) : TmEvent

    data class CardTapped(val source: SourceId) : TmEvent

    data object EmptyTapped : TmEvent

    data class CardYaw(val source: SourceId, val yaw: Float, val settled: Boolean) : TmEvent

    // body panel
    data object QuietNow : TmEvent

    data class MuteSet(val muted: Boolean) : TmEvent

    data object MenuToggle : TmEvent

    data class CapsuleSource(val source: SourceId) : TmEvent

    data object AutoPlace : TmEvent

    data object Start : TmEvent

    data object Nearer : TmEvent

    data object Farther : TmEvent

    data object DefaultSpot : TmEvent

    data object DeletePin : TmEvent

    data object AdjustDone : TmEvent

    data object SkipToUnload : TmEvent

    data object DoneUnloading : TmEvent

    data object DoRest : TmEvent

    data object SkipRedo : TmEvent

    // front card
    data object Continue : TmEvent

    data object GoUnload : TmEvent

    data object Exit : TmEvent

    data object Again : TmEvent

    data object About : TmEvent

    data object AboutOk : TmEvent

    data class IntensitySet(val intensity: Intensity) : TmEvent

    data object ConfirmClear : TmEvent

    data object CancelClear : TmEvent

    // unload card
    data class UnloadChoose(val source: SourceId, val option: Int) : TmEvent

    data object CloseCard : TmEvent

    // menu
    data class LanguageSet(val language: LanguagePref) : TmEvent

    data class MenuPageSet(val page: MenuPage) : TmEvent

    data class RoomStep(val what: Char, val dir: Int) : TmEvent

    data object ResetAroundMe : TmEvent

    data object Repin : TmEvent

    data object ClearHome : TmEvent

    // app
    data object WentBackground : TmEvent

    data object CameForeground : TmEvent

    // debug (QA hooks; the same paths as the UI)
    data class DebugGoto(val state: String) : TmEvent

    data class DebugPin(val source: SourceId, val local: Vec3, val yawDeg: Float?) : TmEvent

    data class DebugLevel(val n: Int) : TmEvent

    data class DebugOverlay(val n: Int) : TmEvent

    data class DebugInput(val mode: InputMode?) : TmEvent

    data class DebugRoom(val size: RoomSize) : TmEvent

    data class DebugSeed(val seed: Int) : TmEvent

    data class DebugElapsed(val task: Int, val quiet: Int?) : TmEvent

    data class DebugInterrupt(val source: SourceId) : TmEvent

    data class DebugHold(val hold: Boolean) : TmEvent

    data class DebugInstant(val instant: Boolean) : TmEvent

    data class DebugTimescale(val k: Float) : TmEvent

    data class DebugAudio(val backend: String) : TmEvent

    data object DebugResetHome : TmEvent
}

/** One-shot effects for the screen. */
sealed interface TmEffect {
    data object ExitApp : TmEffect

    data object Snap : TmEffect

    data object Ding : TmEffect
}
