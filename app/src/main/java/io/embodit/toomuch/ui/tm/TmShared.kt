package io.embodit.toomuch.ui.tm

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.embodit.toomuch.domain.usecase.LevelGate
import io.embodit.toomuch.platform.TmGraph
import io.embodit.toomuch.platform.TmLog

/**
 * What the Stage and the main window share. They are two compositions in one process (factory snippet
 * native-window-move §3.4): the ViewModel lives here, not in either container's `viewModel()`, so closing the window
 * with its × keeps the experience going (and "Quiet now" stays on the Stage chip).
 */
object TmShared {
    /** Main window container id and its fixed tag (one instance, reused). */
    const val WINDOW_ID = "TooMuchWindow"
    const val WINDOW_TAG = "main"

    /** Window size: the tallest page (settings, the intro) fits without scrolling; <= 640 dp (factory D16). */
    const val WINDOW_W_DP = 600
    const val WINDOW_H_DP = 640

    @Volatile private var vm: TmViewModel? = null

    fun viewModel(context: Context): TmViewModel {
        TmGraph.appContext = context.applicationContext
        return vm ?: synchronized(this) { vm ?: TmViewModel(TmGraph.settings(context.applicationContext), TmGraph.anchors).also { vm = it } }
    }

    /** Last lifecycle event the main window reported (`never` until it first opens). */
    var window by mutableStateOf("never")
        private set

    fun windowEvent(name: String) {
        if (window != name) TmLog.i("WINDOW $name")
        window = name
    }

    /** The window is on screen (not closed with its ×, not minimised). */
    val windowOpen: Boolean
        get() = window == "ON_CREATE" || window == "ON_START" || window == "ON_RESUME" || window == "ON_PAUSE"

    /** [Bring panel here] closed the window and waits for its ON_DESTROY to open it again (the chip stays hidden meanwhile). */
    var recallPending by mutableStateOf(false)

    /** Minimised (ON_STOP, not destroyed): the chip restores it instead of opening a new one (native-window-move §2). */
    val windowMinimised: Boolean
        get() = window == "ON_STOP"

    /** Closed with its × or minimised (not "not opened yet": the window takes 3 - 6 s to appear at start). */
    val windowClosed: Boolean
        get() = window == "ON_DESTROY" || window == "ON_STOP"

    // ---- opening only with the head level (native-window-move §7 item 9, D9) -----------------

    private val gate = LevelGate()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastStepMs = 0L

    /** The real open, set by the Stage screen (it holds the navigator); null while no Stage is composed. */
    @Volatile var opener: (() -> Unit)? = null

    /** "Look straight ahead" wanted on the line of sight (the Stage's chip slot shows it). */
    var levelHint by mutableStateOf(false)
        private set

    /** An open is waiting for a level head. */
    val openWaiting: Boolean
        get() = gate.waiting

    /** Asks for the window (Stage start, the "open panel" chip, the reopen of [Bring panel here], debug `window open`). */
    fun askOpen(why: String) {
        TmLog.i("WINDOW ask why=$why")
        gate.ask(why, SystemClock.elapsedRealtime())
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 3_000L)
    }

    /** Every Stage frame: the head pitch in degrees (+ = up), null without a valid head pose. */
    fun stepGate(now: Long, pitchDeg: Float?) {
        lastStepMs = now
        val hint = gate.hintWanted(pitchDeg)
        if (hint != levelHint) TmLog.i("LEVEL hint=$hint pitch=${pitchDeg?.let { "%.1f".format(it) }}")
        levelHint = hint
        gate.step(now, pitchDeg)?.let {
            levelHint = false
            TmLog.i("LEVEL ok pitch=${pitchDeg?.let { p -> "%.1f".format(p) }} -> open")
            openNow(it)
        }
    }

    fun cancelOpen() {
        gate.cancel()
        levelHint = false
        main.removeCallbacks(watchdog)
    }

    /** When the last open went out: the window takes 3 - 6 s to appear, the chip stays away meanwhile. */
    @Volatile var openedMs = 0L
        private set

    private fun openNow(why: String) {
        main.removeCallbacks(watchdog)
        TmLog.i("WINDOW open why=$why")
        openedMs = SystemClock.elapsedRealtime()
        opener?.invoke()
    }

    // a Stage without frames (not up yet, in the background) must not keep the window shut: 15 s after the ask if no
    // frame came at all, else 3 s without a frame
    private val watchdog =
        object : Runnable {
            override fun run() {
                if (!gate.waiting) return
                val now = SystemClock.elapsedRealtime()
                val stalled = if (lastStepMs == 0L) gate.waitedMs(now) > 15_000L else now - lastStepMs > 3_000L
                if (stalled) {
                    val why = gate.pending ?: return
                    gate.cancel()
                    levelHint = false
                    openNow("$why/noframes")
                } else {
                    main.postDelayed(this, 3_000L)
                }
            }
        }
}
