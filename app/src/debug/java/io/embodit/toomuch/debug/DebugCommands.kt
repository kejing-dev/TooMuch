package io.embodit.toomuch.debug

import android.os.Handler
import android.os.Looper
import io.embodit.toomuch.domain.model.InputMode
import io.embodit.toomuch.domain.model.Intensity
import io.embodit.toomuch.domain.model.LanguagePref
import io.embodit.toomuch.domain.model.RoomSize
import io.embodit.toomuch.domain.model.SourceId
import io.embodit.toomuch.domain.model.Vec3
import io.embodit.toomuch.platform.TmLog
import io.embodit.toomuch.ui.tm.TmEvent
import io.embodit.toomuch.ui.tm.debug.TmProbe

/**
 * The QA hooks of design `qaHooks.broadcast`. Every command goes through the same [TmEvent] the panels
 * send (memory-palace lesson: no shortcuts); results are logged under the tag `TooMuch` as `CMD ...`.
 * Generic switches on any command: `--ez instant true` (skip eases: screenshots only), `--ez hold true`
 * (freeze automatic transitions and the 8 s line swap).
 */
object DebugCommands {
    private val main = Handler(Looper.getMainLooper())

    fun run(cmd: String, x: Map<String, Any>) = main.post { runOnMain(cmd.trim(), x) }

    private fun str(x: Map<String, Any>, k: String): String? = x[k]?.toString()

    private fun float(x: Map<String, Any>, k: String): Float? =
        when (val v = x[k]) {
            is Number -> v.toFloat()
            is String -> v.toFloatOrNull()
            else -> null
        }

    private fun int(x: Map<String, Any>, k: String): Int? = float(x, k)?.toInt()

    private fun bool(x: Map<String, Any>, k: String): Boolean? =
        when (val v = x[k]) {
            is Boolean -> v
            is String -> v.toBooleanStrictOrNull()
            else -> null
        }

    private fun runOnMain(cmd: String, x: Map<String, Any>) {
        val host = TmProbe.host
        if (host == null) {
            TmLog.w("CMD $cmd ignored: the Stage is not composed")
            return
        }
        fun send(e: TmEvent) = host.dispatch(e)
        bool(x, "instant")?.let { send(TmEvent.DebugInstant(it)) }
        bool(x, "hold")?.let { send(TmEvent.DebugHold(it)) }
        val id = str(x, "id")
        when (cmd) {
            "goto" -> send(TmEvent.DebugGoto(str(x, "s") ?: "S03"))
            "pin" -> {
                val src = SourceId.fromTag(id) ?: return TmLog.w("CMD pin: unknown id $id")
                send(TmEvent.DebugPin(src, Vec3(float(x, "x") ?: 0f, float(x, "y") ?: 1.5f, float(x, "z") ?: -2f), float(x, "yaw")))
            }
            "autoplace" -> send(TmEvent.AutoPlace)
            "start" -> send(TmEvent.Start)
            "level" -> send(TmEvent.DebugLevel(int(x, "n") ?: 0))
            "overlay" -> send(TmEvent.DebugOverlay(int(x, "n") ?: -1))
            "quiet" -> send(TmEvent.QuietNow)
            "continue" -> send(TmEvent.Continue)
            "lang" -> send(TmEvent.LanguageSet(LanguagePref.fromTag(id ?: "system")))
            "intensity" -> send(TmEvent.IntensitySet(Intensity.fromTag(id)))
            "mute" -> send(TmEvent.MuteSet(bool(x, "on") ?: true))
            "input" -> send(TmEvent.DebugInput(when (id) { "controller" -> InputMode.CONTROLLER; "hands" -> InputMode.HANDS; else -> null }))
            "room" -> {
                val r = host.uiState().room
                send(TmEvent.DebugRoom(RoomSize(length = float(x, "d") ?: r.length, width = float(x, "w") ?: r.width, height = float(x, "h") ?: r.height, floorOffset = float(x, "floor") ?: r.floorOffset)))
            }
            "unload" -> {
                val src = SourceId.fromTag(id) ?: return TmLog.w("CMD unload: unknown id $id")
                send(TmEvent.UnloadChoose(src, int(x, "opt") ?: 0))
            }
            "card" -> SourceId.fromTag(id)?.let { send(TmEvent.CapsuleSource(it)) }
            "audio" -> send(TmEvent.DebugAudio(if (id == "soundpool") "soundpool" else "object"))
            "reset_home" -> send(TmEvent.DebugResetHome)
            "seed" -> send(TmEvent.DebugSeed(int(x, "n") ?: 1))
            "timescale" -> send(TmEvent.DebugTimescale(float(x, "k") ?: 1f))
            "elapsed" -> send(TmEvent.DebugElapsed(int(x, "task") ?: 160, int(x, "quiet")))
            "interrupt" -> send(TmEvent.DebugInterrupt(SourceId.fromTag(id) ?: SourceId.BAG))
            "menu" -> send(TmEvent.MenuToggle)
            "room_page" -> send(TmEvent.MenuPageSet(if (bool(x, "on") == false) io.embodit.toomuch.ui.tm.MenuPage.MAIN else io.embodit.toomuch.ui.tm.MenuPage.ROOM))
            "reset_me" -> send(TmEvent.ResetAroundMe)
            "state" -> {
                val on = bool(x, "on")
                val scene = host.scene()
                if (on != null) TmLog.i("CMD ${scene?.setStateLines(on)}") else scene?.stateLine()?.forEach { TmLog.i(it) }
            }
            "panels" -> host.scene()?.panelLines()?.forEach { TmLog.i(it) }
            "pose" -> TmLog.i(host.scene()?.poseLine() ?: "POSE none")
            "park" -> TmLog.i("CMD ${host.scene()?.parkPanel()}")
            "uvtest" -> TmLog.i("CMD ${host.scene()?.setUvTest(bool(x, "on") ?: true)}")
            "ui" -> {
                val name = id ?: str(x, "name") ?: ""
                TmLog.i("CMD ui $name pressed=${host.pressControl(name)}")
            }
            "controls" -> {
                val panel = id ?: "body"
                TmProbe.controlsOf(panel).forEach { (n, b) -> TmLog.i("CONTROL panel=$panel name=$n cx=${b.cx} cy=${b.cy} w=${b.w} h=${b.h}") }
            }
            "instant", "hold" -> Unit
            else -> TmLog.w("CMD unknown '$cmd'")
        }
    }
}
