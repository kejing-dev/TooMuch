package io.embodit.toomuch.domain.engine

import kotlin.math.abs
import kotlin.math.hypot

/** Things on the tray (design `entities3d.trayItems`). */
enum class ItemId(val tag: String, val candidate: Boolean, val returnable: Boolean) {
    ENVELOPE1("envelope1", true, false),
    ENVELOPE2("envelope2", true, false),
    NOTEBOOK("notebook", true, false),
    SCARF("scarf", true, false),
    MUG("mug", false, true),
    GLASSES("glasses", false, true),
    BOOK("book", false, true),
    REMOTE("remote", false, false),
    KEYS("keys", false, false);

    companion object {
        fun fromTag(tag: String?): ItemId? = entries.firstOrNull { it.tag == tag }
    }
}

/** A spot on the tray: tray-local metres (x to the user's right, z towards the user, on the board) and a yaw. */
data class TraySpot(val x: Float, val z: Float, val yaw: Float = 0f)

/** Item sizes (x, y, z) in metres. */
object TrayLayout {
    const val BOARD_W = 0.80f
    const val BOARD_D = 0.50f
    const val BOARD_T = 0.02f
    const val LIFT_M = 0.10f
    const val LIFT_S = 0.4f
    const val HOLD_TO_REVEAL_S = 1.5f
    const val KEY_FLY_S = 0.6f
    const val SNAP_ANIM_S = 0.3f

    /** A tap without a hold lifts the item for this long (the emulator mouse cannot hold a pinch). */
    const val PEEK_S = 1.7f

    val size: Map<ItemId, Triple<Float, Float, Float>> =
        mapOf(
            ItemId.ENVELOPE1 to Triple(0.22f, 0.005f, 0.11f),
            ItemId.ENVELOPE2 to Triple(0.22f, 0.005f, 0.11f),
            ItemId.NOTEBOOK to Triple(0.18f, 0.015f, 0.12f),
            ItemId.SCARF to Triple(0.25f, 0.04f, 0.15f),
            ItemId.MUG to Triple(0.08f, 0.095f, 0.08f),
            ItemId.GLASSES to Triple(0.14f, 0.03f, 0.04f),
            ItemId.BOOK to Triple(0.18f, 0.03f, 0.12f),
            ItemId.REMOTE to Triple(0.16f, 0.02f, 0.045f),
            ItemId.KEYS to Triple(0.09f, 0.01f, 0.06f),
        )

    /** The four candidate slots; the seed only permutes which candidate lies where. */
    val candidateSlots = listOf(TraySpot(-0.27f, -0.10f), TraySpot(-0.06f, -0.13f), TraySpot(-0.26f, 0.13f), TraySpot(-0.05f, 0.12f))

    /** Start spots of the other items. */
    val fixedSpots: Map<ItemId, TraySpot> =
        mapOf(
            ItemId.MUG to TraySpot(0.11f, -0.15f),
            ItemId.GLASSES to TraySpot(0.12f, 0.00f, 0.25f),
            ItemId.BOOK to TraySpot(0.10f, 0.16f, -0.2f),
            ItemId.REMOTE to TraySpot(-0.06f, 0.00f, 0.1f),
        )

    /** The three outlines on the right (mug Ø 0.09, glasses 0.14 x 0.05, book 0.18 x 0.12). */
    val returnFrames: Map<ItemId, TraySpot> =
        mapOf(
            ItemId.MUG to TraySpot(0.29f, -0.15f),
            ItemId.GLASSES to TraySpot(0.29f, -0.02f),
            ItemId.BOOK to TraySpot(0.29f, 0.14f),
        )

    /** The key hook, at the left back corner. */
    val hook = TraySpot(-0.36f, -0.21f)

    private val candidates = listOf(ItemId.NOTEBOOK, ItemId.SCARF, ItemId.ENVELOPE1, ItemId.ENVELOPE2)

    /** Candidate order for seeds 1..4 (cyclic shifts plus one swap: four different layouts). */
    fun candidatesFor(seed: Int): List<ItemId> {
        val s = ((seed - 1) % 4 + 4) % 4
        val shifted = candidates.drop(s) + candidates.take(s)
        return if (s % 2 == 1) listOf(shifted[1], shifted[0], shifted[3], shifted[2]) else shifted
    }

    /** Snap threshold for putting things back: 5 cm at L0 down to 2 cm at L4; 5 cm in the quiet redo. */
    fun snapThreshold(level: Int, quiet: Boolean): Float = if (quiet) 0.05f else 0.05f - 0.0075f * level.coerceIn(0, 4)
}

/** What the task reports back (the engine turns some into level actions). */
sealed interface TaskEvent {
    data class Lifted(val item: ItemId, val firstTime: Boolean) : TaskEvent

    data class Revealed(val under: ItemId) : TaskEvent

    data object KeyHung : TaskEvent

    data class Snapped(val item: ItemId) : TaskEvent

    data object AllReturned : TaskEvent

    data class Dropped(val item: ItemId) : TaskEvent
}

/**
 * The tray task (design `audio.taskPacing`): hold a candidate up 1.5 s to see what lies below; the keys
 * lie under the fourth different candidate turned over, and only once the level is L3 or more (outside
 * the quiet redo); tap the keys to hang them on the hook; then drag mug, glasses and book into their
 * outlines (snap threshold by level). A bag or phone shot drops whatever is held back where it was.
 * Pure Kotlin; driven by the scene (presses, drags) and the engine (time, level, interruptions).
 */
class TrayTask(val seed: Int, val quiet: Boolean) {
    enum class Stage { KEYS, RETURN, DONE }

    inner class Item(val id: ItemId, var rest: TraySpot) {
        /** Where it is drawn now (tray-local), the lift height above the board and its yaw. */
        var x = rest.x
        var z = rest.z
        var yaw = rest.yaw
        var lift = 0f
        var snapped = false
        var hung = false
        var visible = id != ItemId.KEYS

        /** Return animation after a drop: from (fx, fz) back to (rest) over LIFT_S. */
        var backT = -1f
        var fromX = 0f
        var fromZ = 0f
    }

    var stage = Stage.KEYS
        private set
    val items: Map<ItemId, Item>
    val candidates: List<ItemId> = TrayLayout.candidatesFor(seed)
    private val flipped = LinkedHashSet<ItemId>()
    private val everLifted = HashSet<ItemId>()

    /** The held item (lifted by a press, or dragged), its hold time and whether it is a drag. */
    var held: ItemId? = null
        private set
    private var holdS = 0f
    private var dragging = false
    private var peekLeftS = -1f
    private var dragStartX = 0f
    private var dragStartZ = 0f
    var keyUnder: ItemId? = null
        private set
    private var keyFlyT = -1f
    private val events = ArrayList<TaskEvent>()

    val flippedCount: Int
        get() = flipped.size

    val keyRevealed: Boolean
        get() = keyUnder != null

    val returnedCount: Int
        get() = items.values.count { it.id.returnable && it.snapped }

    init {
        val map = LinkedHashMap<ItemId, Item>()
        candidates.forEachIndexed { i, id -> map[id] = Item(id, TrayLayout.candidateSlots[i]) }
        for ((id, spot) in TrayLayout.fixedSpots) map[id] = Item(id, spot)
        map[ItemId.KEYS] = Item(ItemId.KEYS, TrayLayout.hook).also { it.visible = false }
        items = map
    }

    fun takeEvents(): List<TaskEvent> {
        if (events.isEmpty()) return emptyList()
        val out = ArrayList(events)
        events.clear()
        return out
    }

    val holding: Boolean
        get() = held != null

    /** Press (trigger / pinch held) on [id]: lifts it, or starts a drag for a returnable item in the return stage. */
    fun press(id: ItemId, drag: Boolean = false): Boolean {
        if (stage == Stage.DONE || held != null) return false
        val item = items[id] ?: return false
        if (id == ItemId.KEYS) {
            tapKey()
            return false
        }
        if (item.snapped && !drag) return false
        held = id
        holdS = 0f
        peekLeftS = -1f
        dragging = drag && id.returnable && stage == Stage.RETURN
        dragStartX = item.x
        dragStartZ = item.z
        item.backT = -1f
        val first = everLifted.add(id)
        events += TaskEvent.Lifted(id, first)
        return true
    }

    /** A tap without a hold: lifts for [TrayLayout.PEEK_S], then lets go by itself. */
    fun peek(id: ItemId) {
        if (id == ItemId.KEYS) {
            tapKey()
            return
        }
        if (press(id)) peekLeftS = TrayLayout.PEEK_S
    }

    /** Lets go: a lifted item eases back down; a dragged one stays where it was put down. */
    fun release() {
        if (held == null) return
        held = null
        peekLeftS = -1f
        dragging = false
    }

    val isDragging: Boolean
        get() = dragging

    /** Live drag: [x], [z] tray-local (the ray on the board, or the system drag mapped onto it). */
    fun dragTo(x: Float, z: Float, level: Int) {
        val id = held ?: return
        if (!dragging) return
        val item = items[id] ?: return
        item.x = x.coerceIn(-TrayLayout.BOARD_W / 2f + 0.03f, TrayLayout.BOARD_W / 2f - 0.03f)
        item.z = z.coerceIn(-TrayLayout.BOARD_D / 2f + 0.03f, TrayLayout.BOARD_D / 2f - 0.03f)
        val frame = TrayLayout.returnFrames[id] ?: return
        val d = hypot(item.x - frame.x, item.z - frame.z)
        val thr = TrayLayout.snapThreshold(level, quiet)
        if (item.snapped) {
            if (d > thr * 1.5f) item.snapped = false
            return
        }
        if (d <= thr) {
            item.x = frame.x
            item.z = frame.z
            item.yaw = frame.yaw
            item.snapped = true
            item.rest = frame
            held = null
            dragging = false
            events += TaskEvent.Snapped(id)
            if (returnedCount == 3) {
                stage = Stage.DONE
                events += TaskEvent.AllReturned
            }
        }
    }

    /** A bag / phone shot: whatever is held goes back where it was. Returns the dropped item. */
    fun interrupt(): ItemId? {
        val id = held ?: return null
        val item = items[id] ?: return null
        held = null
        peekLeftS = -1f
        if (dragging) {
            dragging = false
            item.backT = 0f
            item.fromX = item.x
            item.fromZ = item.z
            item.rest = TraySpot(dragStartX, dragStartZ, item.yaw)
        }
        events += TaskEvent.Dropped(id)
        return id
    }

    fun tapKey() {
        if (keyUnder == null || stage != Stage.KEYS || keyFlyT >= 0f) return
        keyFlyT = 0f
    }

    /** Advances by [dt] scaled seconds at [level]. */
    fun step(dt: Float, level: Int) {
        // lifts ease up while held (not while dragged flat), down otherwise
        for (item in items.values) {
            if (item.id == ItemId.KEYS) continue
            val up = item.id == held && !dragging
            val rate = TrayLayout.LIFT_M / TrayLayout.LIFT_S
            item.lift = if (up) (item.lift + rate * dt).coerceAtMost(TrayLayout.LIFT_M) else (item.lift - rate * dt).coerceAtLeast(0f)
            if (item.backT >= 0f) {
                item.backT += dt
                val q = (item.backT / TrayLayout.LIFT_S).coerceIn(0f, 1f)
                item.x = item.fromX + (item.rest.x - item.fromX) * q
                item.z = item.fromZ + (item.rest.z - item.fromZ) * q
                if (q >= 1f) item.backT = -1f
            }
        }
        val id = held
        if (id != null) {
            holdS += dt
            val item = items[id]!!
            if (!dragging && id.candidate && holdS >= TrayLayout.HOLD_TO_REVEAL_S && keyUnder == null) {
                flipped.add(id)
                if (flipped.size >= 4 && (quiet || level >= KEY_LEVEL)) {
                    keyUnder = id
                    val key = items[ItemId.KEYS]!!
                    key.visible = true
                    key.x = item.rest.x
                    key.z = (item.rest.z + 0.09f).coerceAtMost(TrayLayout.BOARD_D / 2f - 0.04f)
                    key.yaw = 0.3f
                    events += TaskEvent.Revealed(id)
                }
            }
            if (peekLeftS >= 0f) {
                peekLeftS -= dt
                if (peekLeftS <= 0f) release()
            }
        }
        if (keyFlyT >= 0f) {
            val key = items[ItemId.KEYS]!!
            val from = keyFlyFrom ?: TraySpot(key.x, key.z).also { keyFlyFrom = it }
            keyFlyT += dt
            val q = (keyFlyT / TrayLayout.KEY_FLY_S).coerceIn(0f, 1f)
            key.x = from.x + (TrayLayout.hook.x - from.x) * q
            key.z = from.z + (TrayLayout.hook.z - from.z) * q
            key.lift = 0.12f * (1f - abs(2f * q - 1f))
            if (q >= 1f) {
                keyFlyT = -1f
                key.hung = true
                key.lift = 0.03f
                stage = Stage.RETURN
                events += TaskEvent.KeyHung
            }
        }
    }

    private var keyFlyFrom: TraySpot? = null

    /** Debug / goto: jump straight to the return stage (keys on the hook). */
    fun skipToReturn() {
        val key = items[ItemId.KEYS]!!
        keyUnder = candidates.last()
        key.visible = true
        key.x = TrayLayout.hook.x
        key.z = TrayLayout.hook.z
        key.hung = true
        key.lift = 0.03f
        stage = Stage.RETURN
    }

    companion object {
        /** The keys turn up only from this level on (outside the quiet redo): see AGENTS.md "deviations". */
        const val KEY_LEVEL = 3
    }
}
