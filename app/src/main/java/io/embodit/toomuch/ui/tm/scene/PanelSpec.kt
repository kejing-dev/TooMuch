package io.embodit.toomuch.ui.tm.scene

import io.embodit.toomuch.domain.model.SourceId

/**
 * Ids and dp sizes of the Stage's attachment panels; the Compose side declares exactly these, once. Since v0.1.1
 * every page is in the main system window: the Stage keeps only the source labels (world text) and the
 * "open panel" chip that brings the window back after its × (with "Quiet now" beside it).
 */
object PanelSpec {
    /** "Open panel" + "Quiet now" while the window is closed; "look straight ahead" while an open waits (D9). */
    const val OPEN = "open"

    /** One name label per source card (world text: design `spatialUiMap.worldText`). */
    fun label(id: SourceId) = "label:${id.tag}"

    val OPEN_SIZE = 520f to 88f
    val LABEL_SIZE = 240f to 56f

    const val OPEN_SCALE = 0.75f

    val labels: List<String> = SourceId.entries.map { label(it) }

    val pool: List<String> = listOf(OPEN) + labels

    fun size(id: String): Pair<Float, Float> = if (id == OPEN) OPEN_SIZE else LABEL_SIZE

    /** Half width / height of a panel in metres at [scale]. */
    fun halfM(id: String, scale: Float): Pair<Float, Float> {
        val (w, h) = size(id)
        return (w * PanelRig.DP_M * scale / 2f) to (h * PanelRig.DP_M * scale / 2f)
    }
}
