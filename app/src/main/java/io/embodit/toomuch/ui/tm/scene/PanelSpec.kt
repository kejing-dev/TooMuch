package io.embodit.toomuch.ui.tm.scene

import io.embodit.toomuch.domain.model.SourceId

/** Ids and dp sizes of every attachment panel (design `panels`); the Compose side declares exactly these, once. */
object PanelSpec {
    const val BODY = "body"
    const val FRONT = "front"
    const val UNLOAD = "unload"
    const val MENU = "menu"
    const val ROOM_TAG = "roomTag"

    /** One name label per source card (world text: design `spatialUiMap.worldText`). */
    fun label(id: SourceId) = "label:${id.tag}"

    val BODY_SIZE = 400f to 464f
    val FRONT_SIZE = 560f to 520f
    val UNLOAD_SIZE = 360f to 280f
    val MENU_SIZE = 400f to 480f
    val LABEL_SIZE = 240f to 56f
    val ROOM_TAG_SIZE = 360f to 56f

    const val BODY_SCALE = 0.75f
    const val FRONT_SCALE = 0.75f
    const val UNLOAD_SCALE = 0.7f
    const val MENU_SCALE = 0.75f

    val labels: List<String> = SourceId.entries.map { label(it) }

    val pool: List<String> = listOf(BODY, FRONT, UNLOAD, MENU, ROOM_TAG) + labels

    fun size(id: String): Pair<Float, Float> =
        when (id) {
            BODY -> BODY_SIZE
            FRONT -> FRONT_SIZE
            UNLOAD -> UNLOAD_SIZE
            MENU -> MENU_SIZE
            ROOM_TAG -> ROOM_TAG_SIZE
            else -> LABEL_SIZE
        }

    /** Half width / height of a panel in metres at [scale]. */
    fun halfM(id: String, scale: Float): Pair<Float, Float> {
        val (w, h) = size(id)
        return (w * PanelRig.DP_M * scale / 2f) to (h * PanelRig.DP_M * scale / 2f)
    }
}
