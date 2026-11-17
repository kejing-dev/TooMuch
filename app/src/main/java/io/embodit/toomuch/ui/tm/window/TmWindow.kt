package io.embodit.toomuch.ui.tm.window

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import io.embodit.toomuch.R
import io.embodit.toomuch.ui.tm.MenuPage
import io.embodit.toomuch.ui.tm.Screen
import io.embodit.toomuch.ui.tm.TmEvent
import io.embodit.toomuch.ui.tm.TmShared
import io.embodit.toomuch.ui.tm.TmUiState
import io.embodit.toomuch.domain.model.SourceId
import io.embodit.toomuch.ui.tm.FrontLayout
import io.embodit.toomuch.ui.tm.components.Capsules
import io.embodit.toomuch.ui.tm.components.SoundGrid
import io.embodit.toomuch.ui.tm.components.TmType
import io.embodit.toomuch.ui.tm.components.FrontContent
import io.embodit.toomuch.ui.tm.components.HowToContent
import io.embodit.toomuch.ui.tm.components.LocalTmResources
import io.embodit.toomuch.ui.tm.components.RoomContent
import io.embodit.toomuch.ui.tm.components.SafetyStrip
import io.embodit.toomuch.ui.tm.components.SettingsContent
import io.embodit.toomuch.ui.tm.components.StepButtons
import io.embodit.toomuch.ui.tm.components.StepTitle
import io.embodit.toomuch.ui.tm.components.UnloadContent
import io.embodit.toomuch.ui.tm.components.fits
import io.embodit.toomuch.ui.tm.components.guide
import io.embodit.toomuch.ui.tm.components.localizedResources
import io.embodit.toomuch.ui.tm.components.rs
import io.embodit.toomuch.ui.tm.debug.LocalProbePanel

/** Page ids of the main window; which one shows follows the state (the settings / room pages are design S13). */
object TmPages {
    const val NOW = "now"
    const val ROOM = "room"
    const val HOWTO = "howto"
    const val SETTINGS = "settings"

    fun of(s: TmUiState): String =
        when {
            !s.menuOpen -> NOW
            s.menuPage == MenuPage.ROOM -> ROOM
            s.menuPage == MenuPage.HOWTO -> HOWTO
            else -> SETTINGS
        }
}

/**
 * Content of the main system window (Planar WindowContainer, `Main.kt`): every 2D part of the app in one window,
 * four TabBar pages: the experience (the step, its card, the unload card), the virtual room, how to (every main
 * action with its hand and controller way, profile B19) and settings. "Quiet now"
 * and mute head every page. Moving / closing is the system window's (rework 2026-11-12, factory native-window-move).
 */
@Composable
fun TmWindow() {
    val context = LocalContext.current
    val viewModel = remember(context) { TmShared.viewModel(context) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    // the Stage shows its "open panel" chip (with "Quiet now") while this window is closed, and recalls it by close + reopen
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> TmShared.windowEvent(e.name) }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            TmShared.windowEvent("ON_DESTROY")
        }
    }

    val resources = remember(state.language, context) { localizedResources(context, state.language) }
    val onEvent = viewModel::onEvent
    val levels = remember(viewModel) { viewModel::liveLevels }
    CompositionLocalProvider(LocalTmResources provides resources, LocalProbePanel provides "window") {
        MainWindow(
            pages =
                listOf(
                    MainWindowPage(TmPages.NOW, rs(R.string.tab_now), R.drawable.ic_volume) { Page(state, onEvent, spread = spreads(state)) { NowPage(state, onEvent, levels) } },
                    MainWindowPage(TmPages.ROOM, rs(R.string.tab_room), R.drawable.ic_straighten) {
                        Page(state, onEvent) {
                            PageHeading(rs(R.string.room_title))
                            RoomContent(state, onEvent)
                        }
                    },
                    MainWindowPage(TmPages.HOWTO, rs(R.string.tab_howto), R.drawable.ic_info) {
                        Page(state, onEvent) {
                            PageHeading(rs(R.string.how_to_title))
                            HowToContent()
                        }
                    },
                    MainWindowPage(TmPages.SETTINGS, rs(R.string.tab_settings), R.drawable.ic_settings) {
                        Page(state, onEvent) { SettingsContent(state, onEvent, onRecall = { onEvent(TmEvent.Recall) }) }
                    },
                ),
            current = TmPages.of(state),
            onSelect = { page -> onEvent(TmEvent.TabSelected(page)) },
        )
    }
}

/**
 * Short experience pages (starting, room ready, unavailable) spread their content over the page instead of
 * centring a small block (QA #2 q2-3: outer whitespace <= 25 % of the window, no inner band > 20 %).
 */
private fun spreads(s: TmUiState): Boolean = s.front == FrontLayout.STARTING || s.front == FrontLayout.UNAVAILABLE || (s.front == null && s.screen == Screen.ROOM_READY)

/** Every page: the safety strip on top, the page's content centred in the rest (or spread, see [spreads]). */
@Composable
private fun Page(state: TmUiState, onEvent: (TmEvent) -> Unit, spread: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp)) {
        SafetyStrip(state, onEvent)
        Spacer(modifier = Modifier.height(16.dp))
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = if (spread) Arrangement.SpaceEvenly else Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
            content = content,
        )
    }
}

/** A plain page heading (the room page). */
@Composable
private fun PageHeading(text: String) {
    Text(text = text, color = PicoTheme.colorScheme.labelPrimary, style = TmType.headline, maxLines = 1, modifier = Modifier.fillMaxWidth(), onTextLayout = fits(text))
}

/**
 * The experience page: the step's title and guide, then its card (intro, quiet, ending ...), the unload card or the
 * capsules. Steps where the sounds play (and the quiet card, the forget-home question) also show the six sounds' live
 * levels, so the short pages carry what is happening rather than blank glass.
 */
@Composable
private fun NowPage(state: TmUiState, onEvent: (TmEvent) -> Unit, levels: () -> Map<SourceId, Int>) {
    StepTitle(state)
    val g = guide(state)
    if (g.isNotEmpty()) {
        Text(text = g, color = PicoTheme.colorScheme.labelPrimary, style = PicoTheme.typography.bodyMedium, maxLines = 3, onTextLayout = fits(g))
    }
    val grid = state.front == FrontLayout.QUIET || state.front == FrontLayout.CONFIRM_CLEAR || state.front == FrontLayout.STARTING || (state.front == null && state.screen in GRID_SCREENS)
    when {
        state.front == FrontLayout.STARTING -> {
            FrontContent(state, onEvent)
            SoundGrid(levels, state.unloaded.keys)
        }
        state.front != null -> {
            if (grid) SoundGrid(levels, state.unloaded.keys)
            FrontContent(state, onEvent)
        }
        state.screen == Screen.UNLOAD && state.cardFor != null -> {
            UnloadContent(state, onEvent)
            StepButtons(state, onEvent)
        }
        state.screen == Screen.ROOM_READY -> {
            val r = state.room
            val size = rs(R.string.room_tag, fmt1(r.length), fmt1(r.width), fmt1(r.height))
            Text(text = size, color = PicoTheme.colorScheme.labelSecondary, style = PicoTheme.typography.bodyMedium, maxLines = 1, onTextLayout = fits(size))
            SoundGrid(levels, state.unloaded.keys)
        }
        else -> {
            Capsules(state, onEvent)
            if (grid) SoundGrid(levels, state.unloaded.keys)
            StepButtons(state, onEvent)
            if (state.screen == Screen.PINNING && state.adjusting == null) {
                val h = rs(R.string.pin_hint)
                Text(text = h, color = PicoTheme.colorScheme.labelSecondary, style = PicoTheme.typography.bodyMedium, maxLines = 2, onTextLayout = fits(h))
            }
        }
    }
}

private val GRID_SCREENS = setOf(Screen.BASELINE, Screen.TASK, Screen.REDO)

private fun fmt1(v: Float): String = String.format(java.util.Locale.US, "%.1f", v)
