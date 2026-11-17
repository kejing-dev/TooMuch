package io.embodit.toomuch.ui.tm.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import androidx.compose.ui.input.pointer.pointerInput
import io.embodit.toomuch.R
import io.embodit.toomuch.domain.model.SourceId
import io.embodit.toomuch.ui.tm.TmEvent
import io.embodit.toomuch.ui.tm.TmShared
import io.embodit.toomuch.ui.tm.TmUiState
import io.embodit.toomuch.ui.tm.debug.LocalProbePanel
import io.embodit.toomuch.ui.tm.scene.PanelSpec
import io.embodit.toomuch.ui.tm.scene.TmScene

/**
 * The Stage content: one full-size SpatialView with the scene's entities and the small Stage panels (every attachment
 * panel declared once, fixed size; [TmScene] parents, shows and places them): the source labels and the chip that
 * opens the main window again after its ×. Taps and drags reach 3D things only (source cards, tray items, the room
 * faces while pinning); the panels moved into the main system window in v0.1.1, so no panel is dragged here.
 */
@Composable
internal fun TmStage(state: TmUiState, scene: TmScene, onEvent: (TmEvent) -> Unit, onOpenWindow: () -> Unit) {
    val context = LocalContext.current
    val bindTick = scene.bindTick.intValue
    SpatialView(
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectSpatialTapGesture(context = context, targetedToEntity = TargetEntity.any()) { value -> scene.onSpatialTap(value) }
                }
                .pointerInput(Unit) {
                    // tray items only (TmScene.onSpatialDrag ignores everything else): the task's own drag, not a panel's
                    detectSpatialDragGesture(
                        context = context,
                        targetedToEntity = TargetEntity.any(),
                        onDragStart = { _, value -> scene.onSpatialDrag("start", value) },
                        onDragEnd = { value -> scene.onSpatialDrag("end", value) },
                        onDragCancel = { value -> scene.onSpatialDrag("cancel", value) },
                    ) { value -> scene.onSpatialDrag("move", value) }
                },
        attachments = {
            AttachmentPanel(PanelSpec.OPEN) {
                PoolPanel(PanelSpec.OPEN, scene) { inView -> OpenWindowChip(state, inView, onEvent, onOpenWindow) }
            }
            for (id in SourceId.entries) {
                AttachmentPanel(PanelSpec.label(id)) {
                    PoolPanel(PanelSpec.label(id), scene) { inView -> LabelPanel(id = id, filled = inView, unloaded = id in state.unloaded) }
                }
            }
        },
        update = { _, attachments ->
            // reading the tick makes this block run again while panel entities are still missing
            @Suppress("UNUSED_EXPRESSION") bindTick
            scene.bindPanels(attachments)
        },
        initial = { content, attachments -> scene.attach(content, attachments) },
    )
}

/** Root of a pooled panel. [content] gets inView: the panel has been moved into view (a pooled panel draws its content only then). */
@Composable
private fun PoolPanel(id: String, scene: TmScene, content: @Composable (inView: Boolean) -> Unit) {
    val inView = scene.panelShown[id] == true
    CompositionLocalProvider(LocalProbePanel provides id) { content(inView) }
}

/**
 * The way back to the main window after its × (factory native-window-move §3.3), with "Quiet now" beside it: the
 * window holds the always-there "Quiet now" (C1), so while it is closed the Stage carries one. While an open waits
 * for a level head the same slot says "look straight ahead" instead (native-window-move §7 item 9).
 */
@Composable
private fun OpenWindowChip(state: TmUiState, inView: Boolean, onEvent: (TmEvent) -> Unit, onOpen: () -> Unit) {
    val (w, h) = PanelSpec.OPEN_SIZE
    Glass(w.dp, h.dp, corner = 32.dp, padding = 14.dp) {
        if (!inView) return@Glass
        if (TmShared.levelHint) {
            val text = rs(R.string.look_level)
            // 20 sp at 0.75 m (~12 mm cap height): readable at a glance while looking down
            Text(text = text, color = PicoTheme.colorScheme.labelPrimary, style = TmType.subhead.copy(lineHeight = 24.sp), textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.fillMaxWidth(), onTextLayout = fits(text))
            return@Glass
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            QuietNowButton(enabled = state.quietEnabled, onClick = { onEvent(TmEvent.QuietNow) }, modifier = Modifier.weight(1f), name = "chip_quiet")
            WordButton("open_window", rs(R.string.btn_openPanel), onOpen, primary = true, minWidth = 200.dp, iconRes = R.drawable.ic_settings)
        }
    }
}
