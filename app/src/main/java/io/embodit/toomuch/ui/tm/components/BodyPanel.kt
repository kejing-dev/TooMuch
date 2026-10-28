package io.embodit.toomuch.ui.tm.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.Text
import io.embodit.toomuch.R
import io.embodit.toomuch.domain.model.InputMode
import io.embodit.toomuch.domain.model.SourceId
import io.embodit.toomuch.ui.tm.GuideOverride
import io.embodit.toomuch.ui.tm.Screen
import io.embodit.toomuch.ui.tm.TaskStage
import io.embodit.toomuch.ui.tm.TmEvent
import io.embodit.toomuch.ui.tm.TmUiState
import io.embodit.toomuch.ui.tm.debug.probe
import io.embodit.toomuch.ui.tm.scene.PanelSpec
import io.embodit.toomuch.ui.theme.TmColors
import kotlin.math.PI
import kotlin.math.cos
import kotlinx.coroutines.delay

internal fun srcName(id: SourceId): Int =
    when (id) {
        SourceId.FRIDGE -> R.string.src_fridge
        SourceId.CLOCK -> R.string.src_clock
        SourceId.BAG -> R.string.src_bag
        SourceId.TAP -> R.string.src_tap
        SourceId.LAMP -> R.string.src_lamp
        SourceId.PHONE -> R.string.src_phone
    }

internal fun srcIcon(id: SourceId): Int =
    when (id) {
        SourceId.FRIDGE -> R.drawable.ic_fridge
        SourceId.CLOCK -> R.drawable.ic_clock
        SourceId.BAG -> R.drawable.ic_bag
        SourceId.TAP -> R.drawable.ic_drop
        SourceId.LAMP -> R.drawable.ic_lamp
        SourceId.PHONE -> R.drawable.ic_phone
    }

/** Above this many characters the guide line switches to three 12 sp lines. */
private const val LONG_GUIDE = 84

/** Segments of the level ring lit in this state (L0 = one segment; not a score). */
private fun ringLit(s: TmUiState): Int =
    when (s.screen) {
        Screen.BASELINE -> 1
        Screen.TASK -> s.level + 1
        Screen.UNLOAD -> 3
        Screen.REDO -> 2
        else -> 0
    }

/**
 * The body panel (design `panels.body`, 400 x 464 at 0.75): stage name + menu; one guide line; level
 * ring + mute switch; "Quiet now" (always on row 4); the 2 x 3 capsule area; two worded buttons.
 */
@Composable
internal fun BodyPanel(state: TmUiState, filled: Boolean, onEvent: (TmEvent) -> Unit) {
    val (w, h) = PanelSpec.BODY_SIZE
    Glass(w.dp, h.dp) {
        if (!filled) return@Glass
        val starting = state.screen == Screen.STARTING
        // row 1: stage name + menu
        Row(modifier = Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = title(state), color = PicoTheme.colorScheme.labelPrimary, style = TmType.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (!starting && state.screen != Screen.UNAVAILABLE) {
                WordButton("body_menu", rs(R.string.btn_menu), { onEvent(TmEvent.MenuToggle) }, minWidth = 96.dp, iconRes = R.drawable.ic_more, selected = state.menuOpen)
            }
        }
        if (starting) return@Glass
        // row 2: guide
        Box(modifier = Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.CenterStart) {
            val g = guide(state)
            // long lines (English) get three tighter lines in the same 44 dp instead of an ellipsis
            val long = g.length > LONG_GUIDE
            val style = if (long) PicoTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 14.sp) else PicoTheme.typography.bodyMedium
            Text(text = g, color = PicoTheme.colorScheme.labelPrimary, style = style, maxLines = if (long) 3 else 2, overflow = TextOverflow.Ellipsis)
        }
        // row 3: level ring + mute
        Row(modifier = Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            LevelRing(lit = ringLit(state))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = rs(R.string.label_volume), color = PicoTheme.colorScheme.labelPrimary, style = PicoTheme.typography.labelLarge)
            Spacer(modifier = Modifier.weight(1f))
            Icon(painter = painterResource(if (state.muted) R.drawable.ic_volume_off else R.drawable.ic_volume), contentDescription = null, modifier = Modifier.size(20.dp), tint = PicoTheme.colorScheme.labelPrimary)
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = rs(R.string.label_mute), color = PicoTheme.colorScheme.labelPrimary, style = PicoTheme.typography.labelLarge)
            Spacer(modifier = Modifier.width(8.dp))
            Switch(checked = state.muted, onCheckedChange = { onEvent(TmEvent.MuteSet(it)) }, modifier = Modifier.probe("body_mute") { onEvent(TmEvent.MuteSet(!state.muted)) })
        }
        // row 4: Quiet now, every state
        QuietButton(state, onEvent)
        // row 5: capsules
        Column(modifier = Modifier.fillMaxWidth().height(160.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Capsules(state, onEvent) }
        // row 6: worded buttons
        Row(modifier = Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) { BottomButtons(state, onEvent) }
    }
}

@Composable
private fun title(s: TmUiState): String =
    when (s.screen) {
        Screen.PINNING -> s.adjusting?.let { rs(R.string.phase_pinAdjust, rs(srcName(it))) } ?: rs(R.string.phase_pinning, s.pinnedCount)
        Screen.BASELINE -> rs(R.string.phase_baseline)
        Screen.TASK -> if (s.plateau) rs(R.string.phase_plateau) else if (s.taskStage == TaskStage.KEYS) rs(R.string.phase_keys) else rs(R.string.phase_return)
        Screen.QUIET -> rs(R.string.phase_quiet)
        Screen.UNLOAD -> rs(R.string.phase_unload)
        Screen.REDO -> rs(R.string.phase_redo)
        Screen.ENDING -> rs(R.string.phase_ending)
        Screen.ABOUT -> rs(R.string.btn_about)
        else -> rs(R.string.phase_intro)
    }

@Composable
private fun guide(s: TmUiState): String =
    when (s.guideOverride) {
        GuideOverride.PLATEAU -> rs(R.string.plateauLine)
        GuideOverride.AUTO_UNLOAD -> rs(R.string.guide_autoUnload)
        else ->
            when (s.screen) {
                Screen.ROOM_READY -> rs(R.string.guide_room)
                Screen.PINNING ->
                    when {
                        s.adjusting != null -> rs(R.string.guide_pinAdjust)
                        s.current == null -> rs(R.string.guide_room)
                        s.input == InputMode.CONTROLLER -> rs(R.string.guide_pinController, rs(srcName(s.current)))
                        else -> rs(R.string.guide_pinHands, rs(srcName(s.current)))
                    }
                Screen.BASELINE -> if (s.reusedHome) rs(R.string.guide_reusedHome) else rs(R.string.guide_baseline)
                Screen.TASK -> rs(R.string.guide_notATest)
                Screen.UNLOAD -> rs(R.string.guide_unload)
                Screen.REDO -> rs(R.string.guide_baseline)
                Screen.QUIET -> rs(R.string.guide_quietDone)
                else -> ""
            }
    }

/** "Quiet now": full width, error container + white, breathing (6 s, 0.5 - 1.0 alpha of the glow) in S03 only. */
@Composable
private fun QuietButton(state: TmUiState, onEvent: (TmEvent) -> Unit) {
    var glow by remember { mutableFloatStateOf(1f) }
    val breathing = state.screen == Screen.INTRO
    LaunchedEffect(breathing) {
        if (!breathing) {
            glow = 1f
            return@LaunchedEffect
        }
        val t0 = System.currentTimeMillis()
        while (true) {
            val t = (System.currentTimeMillis() - t0) / 1000.0
            glow = (0.75 + 0.25 * cos(2 * PI * t / 6.0)).toFloat()
            delay(100L)
        }
    }
    val enabled = state.quietEnabled
    val onClick = { onEvent(TmEvent.QuietNow) }
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(56.dp).alpha(if (enabled) glow else 1f).probe("body_quiet", onClick),
        size = ButtonDefaults.Regular.copy(height = 56.dp, minWidth = 368.dp),
        shape = RoundedCornerShape(28.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = if (enabled) PicoTheme.colorScheme.error else PicoTheme.colorScheme.fillTertiary, contentColor = TmColors.White),
    ) {
        Icon(painter = painterResource(R.drawable.ic_quiet), contentDescription = null, modifier = Modifier.size(24.dp), tint = if (enabled) TmColors.White else PicoTheme.colorScheme.labelQuaternary)
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = rs(R.string.btn_quietNow), color = if (enabled) TmColors.White else PicoTheme.colorScheme.labelQuaternary, style = TmType.title)
    }
}

@Composable
private fun CapsuleGrid(items: List<@Composable () -> Unit>) {
    for (row in items.chunked(2)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { row.forEach { it() } }
    }
}

@Composable
private fun Capsules(s: TmUiState, onEvent: (TmEvent) -> Unit) {
    when (s.screen) {
        Screen.PINNING ->
            if (s.adjusting != null) {
                CapsuleGrid(
                    listOf(
                        { Capsule("adj_nearer", rs(R.string.btn_nearer), R.drawable.ic_nearer, { onEvent(TmEvent.Nearer) }) },
                        { Capsule("adj_farther", rs(R.string.btn_farther), R.drawable.ic_farther, { onEvent(TmEvent.Farther) }) },
                        { Capsule("adj_default", rs(R.string.btn_defaultSpot), R.drawable.ic_pin, { onEvent(TmEvent.DefaultSpot) }) },
                        { Capsule("adj_delete", rs(R.string.btn_delete), R.drawable.ic_trash, { onEvent(TmEvent.DeletePin) }) },
                    )
                )
            } else {
                CapsuleGrid(SourceId.entries.map { id -> { Capsule("cap_${id.tag}", rs(srcName(id)), srcIcon(id), { onEvent(TmEvent.CapsuleSource(id)) }, selected = s.current == id, done = id in s.pinned, open = id !in s.pinned) } })
            }
        Screen.TASK, Screen.REDO -> {
            val keysDone = s.taskStage == TaskStage.RETURN
            CapsuleGrid(
                listOf(
                    { Capsule("task_keys", rs(R.string.task_keys), R.drawable.ic_key, {}, selected = !keysDone, done = keysDone) },
                    { Capsule("task_return", rs(R.string.task_return), R.drawable.ic_pin, {}, selected = keysDone, open = !keysDone) },
                )
            )
        }
        Screen.UNLOAD -> CapsuleGrid(SourceId.entries.map { id -> { Capsule("unl_${id.tag}", rs(srcName(id)), srcIcon(id), { onEvent(TmEvent.CapsuleSource(id)) }, selected = s.cardFor == id, done = id in s.unloaded, open = id !in s.unloaded) } })
        else -> Unit
    }
}

@Composable
private fun BottomButtons(s: TmUiState, onEvent: (TmEvent) -> Unit) {
    val mod = Modifier.width(176.dp)
    when (s.screen) {
        Screen.PINNING ->
            if (s.adjusting != null) {
                WordButton("adj_done", rs(R.string.btn_doneAdjust), { onEvent(TmEvent.AdjustDone) }, modifier = mod, primary = true)
            } else {
                WordButton("pin_auto", rs(R.string.btn_autoPlace), { onEvent(TmEvent.AutoPlace) }, modifier = mod)
                WordButton("pin_start", rs(R.string.btn_start), { onEvent(TmEvent.Start) }, modifier = mod, primary = true, enabled = s.pinnedCount >= 3)
            }
        Screen.BASELINE -> WordButton("base_start", rs(R.string.btn_start), { onEvent(TmEvent.Start) }, modifier = mod, primary = true)
        Screen.TASK -> WordButton("task_skip", rs(R.string.btn_skipToUnload), { onEvent(TmEvent.SkipToUnload) }, modifier = mod)
        Screen.UNLOAD -> {
            WordButton("unl_done", rs(R.string.btn_doneUnload), { onEvent(TmEvent.DoneUnloading) }, modifier = mod, primary = true, enabled = s.unloadedCount >= 3)
            if (s.helpReady) WordButton("unl_rest", rs(R.string.btn_doRest), { onEvent(TmEvent.DoRest) }, modifier = mod)
        }
        Screen.REDO -> WordButton("redo_skip", rs(R.string.btn_skip), { onEvent(TmEvent.SkipRedo) }, modifier = mod)
        Screen.ENDING -> WordButton("end_exit", rs(R.string.btn_exit), { onEvent(TmEvent.Exit) }, modifier = mod)
        else -> Unit
    }
}

/** Five arc segments, L0 - L4, coloured grey-green to dark red (design `palette.levelColors`). */
@Composable
internal fun LevelRing(lit: Int) {
    val dim = PicoTheme.colorScheme.fillSecondary
    Canvas(modifier = Modifier.size(36.dp)) {
        val stroke = 5.dp.toPx()
        val inset = stroke / 2f
        val sweep = 54f
        for (i in 0 until 5) {
            val color = if (i < lit) TmColors.Level[i] else dim
            drawArc(color = color, startAngle = 135f + i * 54f + 2f, sweepAngle = sweep - 4f, useCenter = false, topLeft = Offset(inset, inset), size = Size(size.width - stroke, size.height - stroke), style = Stroke(width = stroke, cap = StrokeCap.Round))
        }
    }
}
