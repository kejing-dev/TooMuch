package io.embodit.toomuch.ui.tm.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.Text
import io.embodit.toomuch.R
import io.embodit.toomuch.domain.model.InputMode
import io.embodit.toomuch.domain.model.SourceId
import io.embodit.toomuch.ui.tm.FrontLayout
import io.embodit.toomuch.ui.tm.GuideOverride
import io.embodit.toomuch.ui.tm.Screen
import io.embodit.toomuch.ui.tm.TaskStage
import io.embodit.toomuch.ui.tm.TmEvent
import io.embodit.toomuch.ui.tm.TmUiState
import io.embodit.toomuch.ui.tm.debug.probe
import io.embodit.toomuch.ui.theme.TmColors
import kotlin.math.PI
import kotlin.math.cos
import kotlinx.coroutines.delay

/*
 * The parts of the former body panel, now laid out by the main window's pages (v0.1.1, factory native-window-move):
 * the safety strip ("Quiet now" + mute, on every page), the step title with the level ring, the guide line, the
 * 2 x 3 capsules and the step's worded buttons.
 */

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

/** Segments of the level ring lit in this state (L0 = one segment; not a score). */
internal fun ringLit(s: TmUiState): Int =
    when (s.screen) {
        Screen.BASELINE -> 1
        Screen.TASK -> s.level + 1
        Screen.UNLOAD -> 3
        Screen.REDO -> 2
        else -> 0
    }

/** The title of the experience page: the front layout's title while one shows, else the step's name. */
@Composable
internal fun pageTitle(s: TmUiState): String =
    when (s.front) {
        FrontLayout.STARTING, FrontLayout.INTRO, FrontLayout.UNAVAILABLE -> rs(R.string.intro_title)
        FrontLayout.QUIET -> rs(R.string.quiet_title)
        FrontLayout.ENDING -> rs(R.string.endingTitle)
        FrontLayout.ABOUT -> rs(R.string.btn_about)
        FrontLayout.CONFIRM_CLEAR -> rs(R.string.btn_clearHome)
        null -> stepTitle(s)
    }

@Composable
private fun stepTitle(s: TmUiState): String =
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

/** The step's one guide line ("" when the front layout speaks for itself). */
@Composable
internal fun guide(s: TmUiState): String =
    when (s.guideOverride) {
        GuideOverride.PLATEAU -> rs(R.string.plateauLine)
        GuideOverride.AUTO_UNLOAD -> rs(R.string.guide_autoUnload)
        else ->
            if (s.front != null) ""
            else
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
                    else -> ""
                }
    }

/**
 * The safety strip at the top of every page of the main window: "Quiet now" (always there, C1) and the mute switch
 * (remembered). The switch sits in a >= 48 dp row that toggles as a whole.
 */
@Composable
internal fun SafetyStrip(state: TmUiState, onEvent: (TmEvent) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        QuietButton(state, onEvent, Modifier.weight(1f))
        val toggle = { onEvent(TmEvent.MuteSet(!state.muted)) }
        Row(
            modifier = Modifier.height(64.dp).clip(RoundedCornerShape(32.dp)).clickable(onClick = toggle).padding(horizontal = 12.dp).probe("body_mute", toggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painter = painterResource(if (state.muted) R.drawable.ic_volume_off else R.drawable.ic_volume), contentDescription = null, modifier = Modifier.size(24.dp), tint = PicoTheme.colorScheme.labelPrimary)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = rs(R.string.label_mute), color = PicoTheme.colorScheme.labelPrimary, style = TmType.title, maxLines = 1)
            Spacer(modifier = Modifier.width(12.dp))
            Switch(checked = state.muted, onCheckedChange = { onEvent(TmEvent.MuteSet(it)) })
        }
    }
}

/** The step title with the level ring in front of it (the ring is not a score: L0 lights one segment). */
@Composable
internal fun StepTitle(state: TmUiState) {
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        val lit = ringLit(state)
        if (lit > 0) {
            LevelRing(lit = lit)
            Spacer(modifier = Modifier.width(12.dp))
        }
        val t = pageTitle(state)
        Text(text = t, color = PicoTheme.colorScheme.labelPrimary, style = TmType.headline, maxLines = 2, modifier = Modifier.weight(1f), onTextLayout = fits(t))
    }
}

/** "Quiet now": error container + white, breathing (6 s, 0.5 - 1.0 alpha of the glow) in S03 only. */
@Composable
private fun QuietButton(state: TmUiState, onEvent: (TmEvent) -> Unit, modifier: Modifier) {
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
    QuietNowButton(enabled = state.quietEnabled, onClick = { onEvent(TmEvent.QuietNow) }, modifier = modifier.alpha(if (state.quietEnabled) glow else 1f), name = "body_quiet")
}

/** The red "Quiet now" capsule (also on the Stage chip while the window is closed). */
@Composable
internal fun QuietNowButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, name: String) {
    // already quiet: an inert grey capsule (the platform's disabled fade made the label vanish)
    val action: () -> Unit = if (enabled) onClick else ({})
    Button(
        onClick = action,
        modifier = modifier.height(64.dp).probe(name, action),
        size = ButtonDefaults.Regular.copy(height = 64.dp, minWidth = 200.dp),
        shape = RoundedCornerShape(32.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (enabled) PicoTheme.colorScheme.error else PicoTheme.colorScheme.fillTertiary, contentColor = TmColors.White),
    ) {
        val content = if (enabled) TmColors.White else PicoTheme.colorScheme.labelSecondary
        Icon(painter = painterResource(R.drawable.ic_quiet), contentDescription = null, modifier = Modifier.size(24.dp), tint = content)
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = rs(R.string.btn_quietNow), color = content, style = TmType.title, maxLines = 1)
    }
}

@Composable
private fun CapsuleGrid(items: List<@Composable () -> Unit>) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        for (row in items.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { row.forEach { it() } }
        }
    }
}

/** The 2 x 3 capsules of the step (sources while pinning / unloading, adjust keys, the task's two steps). */
@Composable
internal fun Capsules(s: TmUiState, onEvent: (TmEvent) -> Unit) {
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

/**
 * The step's worded buttons. "Skip to unloading" ends the task half-way, so it asks first, in place (factory D44:
 * an entry that loses progress is confirmed; the confirmation stays in this window).
 */
@Composable
internal fun StepButtons(s: TmUiState, onEvent: (TmEvent) -> Unit) {
    val mod = Modifier.width(CapsuleWidth)
    var confirmSkip by remember { mutableStateOf(false) }
    if (s.screen != Screen.TASK) confirmSkip = false
    if (confirmSkip) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            val q = rs(R.string.skip_confirm)
            Text(text = q, color = PicoTheme.colorScheme.labelPrimary, style = TmType.subhead, maxLines = 2, onTextLayout = fits(q))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                WordButton("skip_no", rs(R.string.btn_keepGoing), { confirmSkip = false }, modifier = mod, primary = true)
                WordButton("skip_yes", rs(R.string.btn_skipToUnload), {
                    confirmSkip = false
                    onEvent(TmEvent.SkipToUnload)
                }, modifier = mod)
            }
        }
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        when (s.screen) {
            Screen.PINNING ->
                if (s.adjusting != null) {
                    WordButton("adj_done", rs(R.string.btn_doneAdjust), { onEvent(TmEvent.AdjustDone) }, modifier = mod, primary = true)
                } else {
                    WordButton("pin_auto", rs(R.string.btn_autoPlace), { onEvent(TmEvent.AutoPlace) }, modifier = mod)
                    WordButton("pin_start", rs(R.string.btn_start), { onEvent(TmEvent.Start) }, modifier = mod, primary = true, enabled = s.pinnedCount >= 3)
                }
            Screen.BASELINE -> WordButton("base_start", rs(R.string.btn_start), { onEvent(TmEvent.Start) }, modifier = mod, primary = true)
            Screen.TASK -> WordButton("task_skip", rs(R.string.btn_skipToUnload), { confirmSkip = true }, modifier = mod)
            Screen.UNLOAD -> {
                WordButton("unl_done", rs(R.string.btn_doneUnload), { onEvent(TmEvent.DoneUnloading) }, modifier = mod, primary = true, enabled = s.unloadedCount >= 3)
                if (s.helpReady) WordButton("unl_rest", rs(R.string.btn_doRest), { onEvent(TmEvent.DoRest) }, modifier = mod)
            }
            Screen.REDO -> WordButton("redo_skip", rs(R.string.btn_skip), { onEvent(TmEvent.SkipRedo) }, modifier = mod)
            else -> Unit
        }
    }
}

/** Five arc segments, L0 - L4, coloured grey-green to dark red (design `palette.levelColors`). */
@Composable
internal fun LevelRing(lit: Int) {
    val dim = PicoTheme.colorScheme.fillSecondary
    Canvas(modifier = Modifier.size(40.dp)) {
        val stroke = 5.dp.toPx()
        val inset = stroke / 2f
        val sweep = 54f
        for (i in 0 until 5) {
            val color = if (i < lit) TmColors.Level[i] else dim
            drawArc(color = color, startAngle = 135f + i * 54f + 2f, sweepAngle = sweep - 4f, useCenter = false, topLeft = Offset(inset, inset), size = Size(size.width - stroke, size.height - stroke), style = Stroke(width = stroke, cap = StrokeCap.Round))
        }
    }
}

/**
 * The six sounds as they are right now (window, short pages): icon, name and five dots lit by the live level
 * ([read], polled 2.5 times a second; 0 while quiet / muted), a tick once unloaded. Not a control and not a score.
 */
@Composable
internal fun SoundGrid(read: () -> Map<SourceId, Int>, unloaded: Set<SourceId>) {
    var lit by remember { mutableStateOf(read()) }
    LaunchedEffect(read) {
        while (true) {
            lit = read()
            delay(400L)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in SourceId.entries.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (id in row) SoundCell(id, lit[id] ?: 0, id in unloaded)
            }
        }
    }
}

@Composable
private fun SoundCell(id: SourceId, lit: Int, unloaded: Boolean) {
    val ink = PicoTheme.colorScheme.labelPrimary
    Row(
        modifier = Modifier.width(CapsuleWidth).height(44.dp).clip(RoundedCornerShape(22.dp)).background(PicoTheme.colorScheme.fillTertiary).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painter = painterResource(srcIcon(id)), contentDescription = null, modifier = Modifier.size(22.dp), tint = ink)
        Spacer(modifier = Modifier.width(8.dp))
        val name = rs(srcName(id))
        Text(text = name, color = ink, style = PicoTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.weight(1f), onTextLayout = fits(name))
        if (unloaded) {
            Icon(painter = painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(18.dp), tint = PicoTheme.colorScheme.passable)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            for (i in 0 until 5) {
                Spacer(modifier = Modifier.size(8.dp).clip(CircleShape).background(if (i < lit) TmColors.Level[i] else PicoTheme.colorScheme.fillSecondary))
            }
        }
    }
}

