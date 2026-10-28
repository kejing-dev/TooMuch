package io.embodit.toomuch.ui.tm.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import io.embodit.toomuch.ui.tm.MenuPage
import io.embodit.toomuch.ui.tm.Screen
import io.embodit.toomuch.ui.tm.TmEvent
import io.embodit.toomuch.ui.tm.TmUiState
import io.embodit.toomuch.ui.tm.debug.probe
import io.embodit.toomuch.ui.tm.scene.PanelSpec
import java.util.Locale

@Composable
private fun Body(text: String, maxLines: Int = 4, bold: Boolean = false, italic: Boolean = false, color: Color = PicoTheme.colorScheme.labelPrimary) {
    Text(
        text = text,
        color = color,
        style = PicoTheme.typography.bodyMedium.copy(fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The one front card (design `panels.frontCard`, 560 x 520 at 0.75, fixed size): starting / intro / quiet / ending / about / unavailable / confirm. */
@Composable
internal fun FrontCard(state: TmUiState, filled: Boolean, onEvent: (TmEvent) -> Unit) {
    val (w, h) = PanelSpec.FRONT_SIZE
    Glass(w.dp, h.dp) {
        if (!filled) return@Glass
        when (state.front) {
            FrontLayout.STARTING -> {
                Text(text = rs(R.string.intro_title), color = PicoTheme.colorScheme.labelPrimary, style = TmType.headline)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = PicoTheme.colorScheme.labelPrimary, progressSize = CircularProgressDefaults.circleProgressSize(24.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Body(rs(R.string.starting), 1)
                }
            }
            FrontLayout.INTRO -> Intro(state, onEvent)
            FrontLayout.QUIET -> {
                Icon(painter = painterResource(R.drawable.ic_quiet), contentDescription = null, modifier = Modifier.size(48.dp), tint = PicoTheme.colorScheme.labelPrimary)
                Text(text = rs(R.string.quiet_title), color = PicoTheme.colorScheme.labelPrimary, style = TmType.headline)
                Body(rs(R.string.quiet_body), 2)
                Spacer(modifier = Modifier.height(8.dp))
                WordButton("quiet_continue", rs(R.string.btn_continue), { onEvent(TmEvent.Continue) }, modifier = Modifier.fillMaxWidth(), primary = true)
                if (state.returnTo == Screen.TASK) WordButton("quiet_unload", rs(R.string.btn_goUnload), { onEvent(TmEvent.GoUnload) }, modifier = Modifier.fillMaxWidth())
                WordButton("quiet_exit", rs(R.string.btn_exit), { onEvent(TmEvent.Exit) }, minWidth = 120.dp)
            }
            FrontLayout.ENDING -> {
                Text(text = rs(R.string.endingTitle), color = PicoTheme.colorScheme.labelPrimary, style = TmType.subhead, maxLines = 2)
                val m = state.taskSeconds / 60
                val sec = state.taskSeconds % 60
                Body(state.quietSeconds?.let { rs(R.string.endingTimes, m, sec, it) } ?: rs(R.string.endingTimesSkipped, m, sec), 1)
                Text(text = rs(R.string.helpTitle), color = PicoTheme.colorScheme.labelSecondary, style = PicoTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (id in listOf(R.string.help_1, R.string.help_2, R.string.help_3, R.string.help_4, R.string.help_5)) Body("· " + rs(id), 1)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    WordButton("end_again", rs(R.string.btn_again), { onEvent(TmEvent.Again) }, modifier = Modifier.width(240.dp), primary = true)
                    WordButton("end_exit_card", rs(R.string.btn_exit), { onEvent(TmEvent.Exit) }, modifier = Modifier.width(160.dp))
                }
                WordButton("end_about", rs(R.string.btn_about), { onEvent(TmEvent.About) }, minWidth = 120.dp, iconRes = R.drawable.ic_info)
            }
            FrontLayout.ABOUT -> {
                Body(rs(R.string.about), 8)
                Body(rs(R.string.about_sources), 6, color = PicoTheme.colorScheme.labelSecondary)
                Body(rs(R.string.about_synth), 1)
                Body(rs(R.string.about_version, BuildConfig.VERSION_NAME), 1, color = PicoTheme.colorScheme.labelSecondary)
                WordButton("about_ok", rs(R.string.btn_ok), { onEvent(TmEvent.AboutOk) }, modifier = Modifier.fillMaxWidth(), primary = true)
            }
            FrontLayout.UNAVAILABLE -> {
                Icon(painter = painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(48.dp), tint = PicoTheme.colorScheme.labelPrimary)
                Text(text = rs(R.string.unavailable), color = PicoTheme.colorScheme.labelPrimary, style = TmType.subhead, maxLines = 3)
                WordButton("unavail_exit", rs(R.string.btn_exit), { onEvent(TmEvent.Exit) }, modifier = Modifier.fillMaxWidth(), primary = true)
            }
            FrontLayout.CONFIRM_CLEAR -> {
                Text(text = rs(R.string.btn_clearHome), color = PicoTheme.colorScheme.labelPrimary, style = TmType.subhead)
                Body(rs(R.string.clearConfirm), 3)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    WordButton("clear_yes", rs(R.string.btn_forget), { onEvent(TmEvent.ConfirmClear) }, modifier = Modifier.width(240.dp), danger = true)
                    WordButton("clear_no", rs(R.string.btn_cancel), { onEvent(TmEvent.CancelClear) }, modifier = Modifier.width(240.dp))
                }
            }
            null -> Unit
        }
    }
}

@Composable
private fun Intro(state: TmUiState, onEvent: (TmEvent) -> Unit) {
    val armed = state.screen == Screen.INTRO_ARMED
    Text(text = rs(R.string.intro_title), color = PicoTheme.colorScheme.labelPrimary, style = TmType.headline)
    Body(rs(R.string.intro_p1), 4)
    Body(rs(R.string.intro_p2), 3)
    Body(if (armed) rs(R.string.intro_p3armed) else rs(R.string.intro_p3), 2, bold = true)
    Text(
        text = if (state.input == InputMode.CONTROLLER) rs(R.string.intro_inputController) else rs(R.string.intro_inputHands),
        color = PicoTheme.colorScheme.labelSecondary,
        style = PicoTheme.typography.labelLarge,
        maxLines = 1,
    )
    Segments(
        items = listOf("intro_gentle" to rs(R.string.intensity_gentle), "intro_standard" to rs(R.string.intensity_standard)),
        selected = state.intensity.ordinal,
        width = 528.dp,
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

/** A SegmentControl whose items share [width]. */
@Composable
internal fun Segments(items: List<Pair<String, String>>, selected: Int, width: androidx.compose.ui.unit.Dp, onSelect: (Int) -> Unit) {
    val itemW = (width - 8.dp) / items.size
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

/** The unload card (design `panels.unloadCard`, 360 x 280 at 0.7). */
@Composable
internal fun UnloadCard(state: TmUiState, filled: Boolean, onEvent: (TmEvent) -> Unit) {
    val (w, h) = PanelSpec.UNLOAD_SIZE
    Glass(w.dp, h.dp, corner = 28.dp) {
        val id = state.cardFor
        if (!filled || id == null) return@Glass
        Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painter = painterResource(srcIcon(id)), contentDescription = null, modifier = Modifier.size(24.dp), tint = PicoTheme.colorScheme.labelPrimary)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = rs(longName(id)), color = PicoTheme.colorScheme.labelPrimary, style = TmType.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Key("card_close", R.drawable.ic_close, rs(R.string.btn_close)) { onEvent(TmEvent.CloseCard) }
        }
        if (state.cardDone) {
            Box(modifier = Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) { Body(rs(R.string.unloadDone), 2) }
            OptionButton("card_ok", rs(R.string.btn_ok), { onEvent(TmEvent.CloseCard) }, primary = true)
            return@Glass
        }
        Box(modifier = Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) { Body(rs(firstPerson(id)), 2, italic = true) }
        val n = Sources[id].options.size
        for (i in 0 until n) {
            val text = rs(optionText(id, i))
            OptionButton("card_opt$i", text, { onEvent(TmEvent.UnloadChoose(id, i)) }, height = if (text.length > 34) 56.dp else 48.dp)
        }
    }
}

/** The menu (design `panels.menu`, 400 x 480 at 0.75), stacked above the body panel. */
@Composable
internal fun MenuPanel(state: TmUiState, filled: Boolean, onEvent: (TmEvent) -> Unit) {
    val (w, h) = PanelSpec.MENU_SIZE
    Glass(w.dp, h.dp) {
        if (!filled) return@Glass
        if (state.menuPage == MenuPage.ROOM) {
            RoomPage(state, onEvent)
            return@Glass
        }
        Segments(listOf("menu_zh" to rs(R.string.lang_zh), "menu_en" to rs(R.string.lang_en)), if (state.language == LanguagePref.EN || (state.language == LanguagePref.SYSTEM && Locale.getDefault().language != "zh")) 1 else 0, 368.dp) {
            onEvent(TmEvent.LanguageSet(if (it == 0) LanguagePref.ZH else LanguagePref.EN))
        }
        Segments(listOf("menu_gentle" to rs(R.string.intensity_gentle), "menu_standard" to rs(R.string.intensity_standard)), state.intensity.ordinal, 368.dp) {
            onEvent(TmEvent.IntensitySet(Intensity.entries[it]))
        }
        val full = Modifier.fillMaxWidth()
        WordButton("menu_room", rs(R.string.btn_roomPage), { onEvent(TmEvent.MenuPageSet(MenuPage.ROOM)) }, modifier = full)
        WordButton("menu_reset", rs(R.string.btn_resetToMe), { onEvent(TmEvent.ResetAroundMe) }, modifier = full, iconRes = R.drawable.ic_reset)
        val locked = state.homeLocked
        WordButton("menu_repin", if (locked) rs(R.string.btn_repinLocked) else rs(R.string.btn_repin), { onEvent(TmEvent.Repin) }, modifier = full, enabled = !locked)
        WordButton("menu_clear", if (locked) rs(R.string.btn_clearHomeLocked) else rs(R.string.btn_clearHome), { onEvent(TmEvent.ClearHome) }, modifier = full, enabled = !locked, iconRes = R.drawable.ic_trash)
        WordButton("menu_about", rs(R.string.btn_about), { onEvent(TmEvent.About) }, modifier = full, iconRes = R.drawable.ic_info)
        WordButton("menu_exit", rs(R.string.btn_exit), { onEvent(TmEvent.Exit) }, modifier = full, iconRes = R.drawable.ic_exit)
    }
}

@Composable
private fun RoomPage(state: TmUiState, onEvent: (TmEvent) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Key("room_back", R.drawable.ic_back, rs(R.string.btn_back)) { onEvent(TmEvent.MenuPageSet(MenuPage.MAIN)) }
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = rs(R.string.room_title), color = PicoTheme.colorScheme.labelPrimary, style = TmType.title)
    }
    val r = state.room
    Stepper("room_l", rs(R.string.room_length), String.format(Locale.US, "%.1f m", r.length)) { onEvent(TmEvent.RoomStep('l', it)) }
    Stepper("room_w", rs(R.string.room_width), String.format(Locale.US, "%.1f m", r.width)) { onEvent(TmEvent.RoomStep('w', it)) }
    Stepper("room_h", rs(R.string.room_height), String.format(Locale.US, "%.1f m", r.height)) { onEvent(TmEvent.RoomStep('h', it)) }
    Stepper("room_f", rs(R.string.room_floor), String.format(Locale.US, "%+.1f m", r.floorOffset)) { onEvent(TmEvent.RoomStep('f', it)) }
    Spacer(modifier = Modifier.height(8.dp))
    WordButton("room_done", rs(R.string.btn_back), { onEvent(TmEvent.MenuPageSet(MenuPage.MAIN)) }, modifier = Modifier.fillMaxWidth(), primary = true)
}

/** Name, minus, value, plus (no SpatialUI stepper: two icon keys and a text, design `spatialUiMap.stepper`). */
@Composable
private fun Stepper(name: String, label: String, value: String, onStep: (Int) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, color = PicoTheme.colorScheme.labelPrimary, style = PicoTheme.typography.labelLarge, modifier = Modifier.weight(1f), maxLines = 1)
        Key("${name}_minus", R.drawable.ic_minus, "-") { onStep(-1) }
        Box(modifier = Modifier.width(96.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Text(text = value, color = PicoTheme.colorScheme.labelPrimary, style = TmType.figure)
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
            Text(text = rs(srcName(id)), color = PicoTheme.colorScheme.labelPrimary, style = PicoTheme.typography.titleLarge.copy(fontSize = androidx.compose.ui.unit.TextUnit(28f, androidx.compose.ui.unit.TextUnitType.Sp), fontWeight = FontWeight.SemiBold), maxLines = 1)
        }
    }
}

/** The room's size on the front wall while the room page is open. */
@Composable
internal fun RoomTag(state: TmUiState, filled: Boolean) {
    val (w, h) = PanelSpec.ROOM_TAG_SIZE
    Glass(w.dp, h.dp, corner = 28.dp, padding = 8.dp) {
        if (!filled) return@Glass
        val r = state.room
        Box(modifier = Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
            Text(text = rs(R.string.room_tag, String.format(Locale.US, "%.1f", r.length), String.format(Locale.US, "%.1f", r.width), String.format(Locale.US, "%.1f", r.height)), color = PicoTheme.colorScheme.labelPrimary, style = TmType.title, maxLines = 1)
        }
    }
}
