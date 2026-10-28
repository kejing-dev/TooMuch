package io.embodit.toomuch.ui.tm.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import io.embodit.toomuch.domain.model.SourceId
import io.embodit.toomuch.ui.tm.TmEvent
import io.embodit.toomuch.ui.tm.TmUiState
import io.embodit.toomuch.ui.tm.debug.LocalProbePanel
import io.embodit.toomuch.ui.tm.scene.PanelSpec
import io.embodit.toomuch.ui.tm.scene.TmScene

/**
 * The Stage content: one full-size SpatialView with the scene's entities and the pre-built panel pool
 * (every attachment panel declared once, fixed size; [TmScene] parents, shows and places them). Tap and
 * drag recognisers live in separate pointerInput blocks (two spatial recognisers must not share one).
 *
 * Window model: single panel with popups (design windowModel): the body panel is the one resident
 * panel; the front card, the unload card and the menu pop up; labels ride above the source cards.
 */
@Composable
internal fun TmStage(state: TmUiState, scene: TmScene, onEvent: (TmEvent) -> Unit) {
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
                    detectSpatialDragGesture(
                        context = context,
                        targetedToEntity = TargetEntity.any(),
                        onDragStart = { _, value -> scene.onSpatialDrag("start", value) },
                        onDragEnd = { value -> scene.onSpatialDrag("end", value) },
                        onDragCancel = { value -> scene.onSpatialDrag("cancel", value) },
                    ) { value -> scene.onSpatialDrag("move", value) }
                },
        attachments = {
            AttachmentPanel(PanelSpec.BODY) {
                PoolPanel(PanelSpec.BODY, scene, watched = true) { inView -> BodyPanel(state = state, filled = inView, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.FRONT) {
                PoolPanel(PanelSpec.FRONT, scene) { inView -> FrontCard(state = state, filled = inView && state.front != null, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.UNLOAD) {
                PoolPanel(PanelSpec.UNLOAD, scene) { inView -> UnloadCard(state = state, filled = inView, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.MENU) {
                PoolPanel(PanelSpec.MENU, scene, watched = true) { inView -> MenuPanel(state = state, filled = inView && state.menuOpen, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.ROOM_TAG) {
                PoolPanel(PanelSpec.ROOM_TAG, scene) { inView -> RoomTag(state = state, filled = inView) }
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

/**
 * Root of a pooled panel. [content] gets inView: the panel has been moved into view (a pooled panel
 * draws its content only then). [watched]: presses on it hold the lazy follow still.
 */
@Composable
private fun PoolPanel(id: String, scene: TmScene, watched: Boolean = false, content: @Composable (inView: Boolean) -> Unit) {
    val inView = scene.panelShown[id] == true
    CompositionLocalProvider(LocalProbePanel provides id) {
        if (watched) {
            Box(
                modifier =
                    Modifier.pointerInput(id) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == PointerEventType.Press) scene.onPanelPointer(true)
                                if (event.type == PointerEventType.Release) scene.onPanelPointer(false)
                            }
                        }
                    }
            ) {
                content(inView)
            }
        } else {
            content(inView)
        }
    }
}
