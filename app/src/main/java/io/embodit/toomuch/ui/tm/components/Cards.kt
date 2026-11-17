package io.embodit.toomuch.ui.tm.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import io.embodit.toomuch.BuildConfig
import io.embodit.toomuch.R
import io.embodit.toomuch.domain.model.InputMode
import io.embodit.toomuch.domain.model.Intensity
import io.embodit.toomuch.domain.model.LanguagePref
import io.embodit.toomuch.domain.model.SourceId
import io.embodit.toomuch.domain.model.Sources
import io.embodit.toomuch.ui.tm.FrontLayout
import io.embodit.toomuch.ui.tm.Screen
import io.embodit.toomuch.ui.tm.TmEvent
import io.embodit.toomuch.ui.tm.TmUiState
import io.embodit.toomuch.ui.tm.debug.probe
import io.embodit.toomuch.ui.tm.scene.PanelSpec
import java.util.Locale

/*
 * The sections of the main window's pages (v0.1.1): what used to be the front card, the unload card and the menu
 * are parts of one system window now (factory native-window-move, taste D8 "one window"). No Glass of their own:
 * the window is the glass.
 */

@Composable
private fun Body(text: String, maxLines: Int = 8, bold: Boolean = false, italic: Boolean = false, color: Color = PicoTheme.colorScheme.labelPrimary) {
    Text(
        text = text,
        color = color,
        style = PicoTheme.typography.bodyMedium.copy(fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = fits(text),
    )
}

/** An inline "are you sure" (factory D44: progress-losing entries ask first, in the same window). */
@Composable
internal fun ConfirmBlock(question: String, cancel: String, ok: String, cancelName: String, okName: String, onCancel: () -> Unit, onOk: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = question, color = PicoTheme.colorScheme.labelPrimary, style = TmType.subhead, maxLines = 2, onTextLayout = fits(question))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            WordButton(cancelName, cancel, onCancel, modifier = Modifier.width(220.dp), primary = true)
            WordButton(okName, ok, onOk, modifier = Modifier.width(220.dp), danger = true)
        }
    }
}

/** The front layouts (starting / intro / quiet / ending / about / unavailable / forget-home confirmation). */
@Composable
internal fun FrontContent(state: TmUiState, onEvent: (TmEvent) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (state.front) {
            FrontLayout.STARTING -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = PicoTheme.colorScheme.labelPrimary, progressSize = CircularProgressDefaults.circleProgressSize(24.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Body(rs(R.string.starting), 1)
                }
                Body(rs(R.string.starting_hint), 2, color = PicoTheme.colorScheme.labelSecondary)
                Body(rs(R.string.intro_p1), 4, color = PicoTheme.colorScheme.labelSecondary)
            }
            FrontLayout.INTRO -> Intro(state, onEvent)
            FrontLayout.QUIET -> Quiet(state, onEvent)
            FrontLayout.ENDING -> {
                val m = state.taskSeconds / 60
                val sec = state.taskSeconds % 60
                Body(state.quietSeconds?.let { rs(R.string.endingTimes, m, sec, it) } ?: rs(R.string.endingTimesSkipped, m, sec), 2)
                Text(text = rs(R.string.helpTitle), color = PicoTheme.colorScheme.labelSecondary, style = PicoTheme.typography.labelLarge)
                // gate:allow button-gap five tip lines of text, not buttons (the buttons below are a Row spaced 16 dp)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (id in listOf(R.string.help_1, R.string.help_2, R.string.help_3, R.string.help_4, R.string.help_5)) Body("· " + rs(id), 2)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    WordButton("end_again", rs(R.string.btn_again), { onEvent(TmEvent.Again) }, modifier = Modifier.width(200.dp), primary = true)
                    WordButton("end_exit_card", rs(R.string.btn_exit), { onEvent(TmEvent.Exit) }, modifier = Modifier.width(140.dp))
                    WordButton("end_about", rs(R.string.btn_about), { onEvent(TmEvent.About) }, modifier = Modifier.width(140.dp), iconRes = R.drawable.ic_info)
                }
            }
            FrontLayout.ABOUT -> {
                Body(rs(R.string.about), 8)
                Body(rs(R.string.about_sources), 6, color = PicoTheme.colorScheme.labelSecondary)
                Body(rs(R.string.about_synth), 2)
                Body(rs(R.string.about_version, BuildConfig.VERSION_NAME), 1, color = PicoTheme.colorScheme.labelSecondary)
                WordButton("about_ok", rs(R.string.btn_ok), { onEvent(TmEvent.AboutOk) }, modifier = Modifier.fillMaxWidth(), primary = true)
            }
            FrontLayout.UNAVAILABLE -> {
                Icon(painter = painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(48.dp), tint = PicoTheme.colorScheme.labelPrimary)
                val t = rs(R.string.unavailable)
                Text(text = t, color = PicoTheme.colorScheme.labelPrimary, style = TmType.subhead, maxLines = 3, onTextLayout = fits(t))
                Body(rs(R.string.unavailable_hint), 3, color = PicoTheme.colorScheme.labelSecondary)
                Body(rs(R.string.intro_p1), 4, color = PicoTheme.colorScheme.labelSecondary)
                WordButton("unavail_exit", rs(R.string.btn_exit), { onEvent(TmEvent.Exit) }, modifier = Modifier.fillMaxWidth(), primary = true)
            }
            FrontLayout.CONFIRM_CLEAR ->
                ConfirmBlock(rs(R.string.clearConfirm), rs(R.string.btn_cancel), rs(R.string.btn_forget), "clear_no", "clear_yes", { onEvent(TmEvent.CancelClear) }, { onEvent(TmEvent.ConfirmClear) })
            null -> Unit
        }
    }
}

@Composable
private fun Intro(state: TmUiState, onEvent: (TmEvent) -> Unit) {
    val armed = state.screen == Screen.INTRO_ARMED
    Body(rs(R.string.intro_p1))
    Body(rs(R.string.intro_p2))
    Body(if (armed) rs(R.string.intro_p3armed) else rs(R.string.intro_p3), 2, bold = true)
    val input = if (state.input == InputMode.CONTROLLER) rs(R.string.intro_inputController) else rs(R.string.intro_inputHands)
    Text(text = input, color = PicoTheme.colorScheme.labelSecondary, style = PicoTheme.typography.labelLarge, maxLines = 2, onTextLayout = fits(input))
    Segments(
        items = listOf("intro_gentle" to rs(R.string.intensity_gentle), "intro_standard" to rs(R.string.intensity_standard)),
        selected = state.intensity.ordinal,
        width = 544.dp,
        onSelect = { onEvent(TmEvent.IntensitySet(Intensity.entries[it])) },
    )
    WordButton(
        "intro_continue",
        if (armed) rs(R.string.btn_continue) else rs(R.string.intro_continueDisabled),
        { onEvent(TmEvent.Continue) },
        modifier = Modifier.fillMaxWidth(),
        primary = armed,
        enabled = armed,
    )
}

/** The quiet card: continue, go unload (from the task), exit; leaving mid-run asks first. */
@Composable
private fun Quiet(state: TmUiState, onEvent: (TmEvent) -> Unit) {
    var confirmQuit by remember { mutableStateOf(false) }
    val midRun = state.returnTo in setOf(Screen.BASELINE, Screen.TASK, Screen.UNLOAD, Screen.REDO)
    Body(rs(R.string.quiet_body), 2)
    Spacer(modifier = Modifier.height(4.dp))
    if (confirmQuit) {
        ConfirmBlock(rs(R.string.quit_confirm), rs(R.string.btn_cancel), rs(R.string.btn_exit), "quiet_exit_no", "quiet_exit_yes", { confirmQuit = false }, { onEvent(TmEvent.Exit) })
        return
    }
    WordButton("quiet_continue", rs(R.string.btn_continue), { onEvent(TmEvent.Continue) }, modifier = Modifier.fillMaxWidth(), primary = true)
    if (state.returnTo == Screen.TASK) WordButton("quiet_unload", rs(R.string.btn_goUnload), { onEvent(TmEvent.GoUnload) }, modifier = Modifier.fillMaxWidth())
    WordButton("quiet_exit", rs(R.string.btn_exit), { if (midRun) confirmQuit = true else onEvent(TmEvent.Exit) }, modifier = Modifier.fillMaxWidth())
}

/** A SegmentControl whose items share [width] (options inside one row of a page, never to switch pages). */
@Composable
internal fun Segments(items: List<Pair<String, String>>, selected: Int, width: Dp, onSelect: (Int) -> Unit) {
    val itemW = (width - 8.dp) / items.size
    // gate:allow tabs-segment two options of one setting inside a page row (language / intensity); pages switch with the native TabBar
    SegmentControl(modifier = Modifier.width(width).height(48.dp), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
        items.forEachIndexed { i, (name, text) ->
            val onClick = { onSelect(i) }
            SegmentItem(
                selected = selected == i,
                onClick = onClick,
                modifier = Modifier.width(itemW).height(48.dp).probe(name, onClick),
                textStyle = PicoTheme.typography.labelLarge,
                colors =
                    SegmentControlDefaults.colors(
                        itemBackgroundColor = Color.Transparent,
                        itemContentColor = PicoTheme.colorScheme.labelPrimary,
                        selectedItemBackgroundColor = PicoTheme.colorScheme.fillSecondary,
                        selectedItemContentColor = PicoTheme.colorScheme.labelPrimary,
                    ),
                title = {
                    // design-style: inherited-content-color SegmentItem
                    Text(text = text, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
                },
            )
        }
    }
}

internal fun optionText(id: SourceId, option: Int): Int =
    when (id) {
        SourceId.FRIDGE -> if (option == 0) R.string.opt_fridge_0 else R.string.opt_fridge_1
        SourceId.CLOCK -> if (option == 0) R.string.opt_clock_0 else R.string.opt_clock_1
        SourceId.BAG -> if (option == 0) R.string.opt_bag_0 else R.string.opt_bag_1
        SourceId.TAP -> R.string.opt_tap_0
        SourceId.LAMP -> if (option == 0) R.string.opt_lamp_0 else R.string.opt_lamp_1
        SourceId.PHONE -> if (option == 0) R.string.opt_phone_0 else R.string.opt_phone_1
    }

private fun longName(id: SourceId): Int =
    when (id) {
        SourceId.FRIDGE -> R.string.src_long_fridge
        SourceId.CLOCK -> R.string.src_long_clock
        SourceId.BAG -> R.string.src_long_bag
        SourceId.TAP -> R.string.src_long_tap
        SourceId.LAMP -> R.string.src_long_lamp
        SourceId.PHONE -> R.string.src_long_phone
    }

private fun firstPerson(id: SourceId): Int =
    when (id) {
        SourceId.FRIDGE -> R.string.fp_fridge
        SourceId.CLOCK -> R.string.fp_clock
        SourceId.BAG -> R.string.fp_bag
        SourceId.TAP -> R.string.fp_tap
        SourceId.LAMP -> R.string.fp_lamp
        SourceId.PHONE -> R.string.fp_phone
    }

/** The unload card's content (design `panels.unloadCard`): the source, its first-person line, the options. */
@Composable
internal fun UnloadContent(state: TmUiState, onEvent: (TmEvent) -> Unit) {
    val id = state.cardFor ?: return
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painter = painterResource(srcIcon(id)), contentDescription = null, modifier = Modifier.size(28.dp), tint = PicoTheme.colorScheme.labelPrimary)
            Spacer(modifier = Modifier.width(10.dp))
            val name = rs(longName(id))
            Text(text = name, color = PicoTheme.colorScheme.labelPrimary, style = TmType.subhead, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), onTextLayout = fits(name))
            Key("card_close", R.drawable.ic_close, rs(R.string.btn_close)) { onEvent(TmEvent.CloseCard) }
        }
        if (state.cardDone) {
            Body(rs(R.string.unloadDone), 2)
            OptionButton("card_ok", rs(R.string.btn_ok), { onEvent(TmEvent.CloseCard) }, primary = true)
            return@Column
        }
        Body(rs(firstPerson(id)), 3, italic = true)
        val n = Sources[id].options.size
        for (i in 0 until n) {
            val text = rs(optionText(id, i))
            OptionButton("card_opt$i", text, { onEvent(TmEvent.UnloadChoose(id, i)) }, height = 64.dp)
        }
    }
}

/** A label + control row of the settings page (>= 48 dp). */
@Composable
private fun SettingRow(label: String, control: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, color = PicoTheme.colorScheme.labelPrimary, style = TmType.title, maxLines = 1, modifier = Modifier.weight(1f), onTextLayout = fits(label))
        control()
    }
}

/**
 * The settings page (the old menu, design S13): language, intensity, reset around me + bring the panel here, re-pin,
 * forget home, about; quitting is always last and asks in place (template D26).
 */
@Composable
internal fun SettingsContent(state: TmUiState, onEvent: (TmEvent) -> Unit, onRecall: () -> Unit) {
    var confirmQuit by remember { mutableStateOf(false) }
    if (confirmQuit) {
        // the question takes the page (with 64 dp rows the settings list has no room left under "Quit app"),
        // spread over it with an icon and what is kept (QA #2 q2-3: the bare question left 39 % blank)
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painter = painterResource(R.drawable.ic_exit), contentDescription = null, modifier = Modifier.size(48.dp), tint = PicoTheme.colorScheme.labelPrimary)
            Body(rs(R.string.quit_keep), 2, color = PicoTheme.colorScheme.labelSecondary)
            ConfirmBlock(rs(R.string.quit_confirm), rs(R.string.btn_cancel), rs(R.string.btn_exit), "exit_no", "exit_yes", { confirmQuit = false }, { onEvent(TmEvent.Exit) })
        }
        return
    }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val english = state.language == LanguagePref.EN || (state.language == LanguagePref.SYSTEM && Locale.getDefault().language != "zh")
        SettingRow(rs(R.string.menu_language)) {
            Segments(listOf("menu_zh" to rs(R.string.lang_zh), "menu_en" to rs(R.string.lang_en)), if (english) 1 else 0, 320.dp) {
                onEvent(TmEvent.LanguageSet(if (it == 0) LanguagePref.ZH else LanguagePref.EN))
            }
        }
        SettingRow(rs(R.string.menu_intensity)) {
            Segments(listOf("menu_gentle" to rs(R.string.intensity_gentle), "menu_standard" to rs(R.string.intensity_standard)), state.intensity.ordinal, 320.dp) {
                onEvent(TmEvent.IntensitySet(Intensity.entries[it]))
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            WordButton("menu_reset", rs(R.string.btn_resetToMe), { onEvent(TmEvent.ResetAroundMe) }, modifier = Modifier.weight(1f), iconRes = R.drawable.ic_reset)
            WordButton("menu_recall", rs(R.string.btn_recall), onRecall, modifier = Modifier.weight(1f), iconRes = R.drawable.ic_my_location)
        }
        val locked = state.homeLocked
        val full = Modifier.fillMaxWidth()
        WordButton("menu_repin", if (locked) rs(R.string.btn_repinLocked) else rs(R.string.btn_repin), { onEvent(TmEvent.Repin) }, modifier = full, enabled = !locked, iconRes = R.drawable.ic_pin)
        WordButton("menu_clear", if (locked) rs(R.string.btn_clearHomeLocked) else rs(R.string.btn_clearHome), { onEvent(TmEvent.ClearHome) }, modifier = full, enabled = !locked, iconRes = R.drawable.ic_trash)
        WordButton("menu_about", rs(R.string.btn_about), { onEvent(TmEvent.About) }, modifier = full, iconRes = R.drawable.ic_info)
        WordButton("menu_exit", rs(R.string.btn_quit), { confirmQuit = true }, modifier = full, iconRes = R.drawable.ic_exit)
    }
}


/** One row of the how-to page: icon, the action, then how with hands and how with a controller. */
private data class HowTo(val icon: Int, val title: Int, val hands: Int, val controller: Int)

private val HOW_TO =
    listOf(
        HowTo(R.drawable.ic_pin, R.string.how_to_pin, R.string.how_to_pin_hands, R.string.how_to_pin_ctrl),
        HowTo(R.drawable.ic_key, R.string.how_to_look, R.string.how_to_look_hands, R.string.how_to_look_ctrl),
        HowTo(R.drawable.ic_drop, R.string.how_to_return, R.string.how_to_return_hands, R.string.how_to_return_ctrl),
        HowTo(R.drawable.ic_check, R.string.how_to_unload, R.string.how_to_unload_hands, R.string.how_to_unload_ctrl),
        HowTo(R.drawable.ic_quiet, R.string.how_to_quiet, R.string.how_to_quiet_hands, R.string.how_to_quiet_ctrl),
        HowTo(R.drawable.ic_my_location, R.string.how_to_panel, R.string.how_to_panel_hands, R.string.how_to_panel_ctrl),
    )

/** The how-to page (profile B19): every main action in one place, one row each, hands and controller. */
@Composable
internal fun HowToContent() {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (h in HOW_TO) {
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                Icon(painter = painterResource(h.icon), contentDescription = null, modifier = Modifier.size(24.dp), tint = PicoTheme.colorScheme.labelPrimary)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    val title = rs(h.title)
                    val hands = rs(R.string.how_to_hands_line, rs(h.hands))
                    val ctrl = rs(R.string.how_to_ctrl_line, rs(h.controller))
                    Text(text = title, color = PicoTheme.colorScheme.labelPrimary, style = PicoTheme.typography.labelLarge, maxLines = 1, onTextLayout = fits(title))
                    Text(text = hands, color = PicoTheme.colorScheme.labelSecondary, style = PicoTheme.typography.bodySmall, maxLines = 2, onTextLayout = fits(hands))
                    Text(text = ctrl, color = PicoTheme.colorScheme.labelSecondary, style = PicoTheme.typography.bodySmall, maxLines = 2, onTextLayout = fits(ctrl))
                }
            }
        }
    }
}

/** The room page: the virtual room's size (its dashed outline shows in the room while this page is open). */
@Composable
internal fun RoomContent(state: TmUiState, onEvent: (TmEvent) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Body(rs(R.string.room_hint), 2, color = PicoTheme.colorScheme.labelSecondary)
        val r = state.room
        Stepper("room_l", rs(R.string.room_length), String.format(Locale.US, "%.1f m", r.length)) { onEvent(TmEvent.RoomStep('l', it)) }
        Stepper("room_w", rs(R.string.room_width), String.format(Locale.US, "%.1f m", r.width)) { onEvent(TmEvent.RoomStep('w', it)) }
        Stepper("room_h", rs(R.string.room_height), String.format(Locale.US, "%.1f m", r.height)) { onEvent(TmEvent.RoomStep('h', it)) }
        Stepper("room_f", rs(R.string.room_floor), String.format(Locale.US, "%+.1f m", r.floorOffset)) { onEvent(TmEvent.RoomStep('f', it)) }
    }
}

/** Name, minus, value, plus (no SpatialUI stepper: two icon keys and a text, design `spatialUiMap.stepper`). */
@Composable
private fun Stepper(name: String, label: String, value: String, onStep: (Int) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, color = PicoTheme.colorScheme.labelPrimary, style = TmType.title, modifier = Modifier.weight(1f), maxLines = 1, onTextLayout = fits(label))
        Key("${name}_minus", R.drawable.ic_minus, "-") { onStep(-1) }
        Box(modifier = Modifier.width(112.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Text(text = value, color = PicoTheme.colorScheme.labelPrimary, style = TmType.subhead.copy(fontFeatureSettings = "tnum, lnum"))
        }
        Key("${name}_plus", R.drawable.ic_plus, "+") { onStep(+1) }
    }
}

/** A source card's name (world text, design `spatialUiMap.worldText`): faces the viewer, sized by distance. */
@Composable
internal fun LabelPanel(id: SourceId, filled: Boolean, unloaded: Boolean) {
    val (w, h) = PanelSpec.LABEL_SIZE
    Glass(w.dp, h.dp, corner = 28.dp, padding = 8.dp) {
        if (!filled) return@Glass
        Box(modifier = Modifier.fillMaxWidth().height(40.dp).alpha(if (unloaded) 0.7f else 1f), contentAlignment = Alignment.Center) {
            Text(text = rs(srcName(id)), color = PicoTheme.colorScheme.labelPrimary, style = PicoTheme.typography.titleLarge.copy(fontSize = TextUnit(28f, TextUnitType.Sp), fontWeight = FontWeight.SemiBold), maxLines = 1)
        }
    }
}
