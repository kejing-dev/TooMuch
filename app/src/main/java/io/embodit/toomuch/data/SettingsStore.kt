package io.embodit.toomuch.data

import android.content.Context
import io.embodit.toomuch.domain.model.Intensity
import io.embodit.toomuch.domain.model.LanguagePref
import io.embodit.toomuch.domain.model.RoomSize
import io.embodit.toomuch.domain.model.SourceId

/** What is kept between launches (design `persistence`). */
data class Settings(
    val language: LanguagePref = LanguagePref.SYSTEM,
    val intensity: Intensity = Intensity.GENTLE,
    val muted: Boolean = false,
    val room: RoomSize = RoomSize(),
    val introSeen: Boolean = false,
    /** Last run's task and quiet times, seconds (only for the log; the ending card shows this run's). */
    val lastTaskS: Int = 0,
)

/** "My home": per source its anchor id and the card's yaw about the vertical. */
data class HomeEntry(val anchorId: String, val yaw: Float)

/**
 * SharedPreferences: settings plus the anchor <-> source map of "my home". The anchors themselves live
 * in the platform's WorldTracking store (names toomuch-<source>).
 */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): Settings =
        Settings(
            language = LanguagePref.fromTag(prefs.getString(KEY_LANG, null)),
            intensity = Intensity.fromTag(prefs.getString(KEY_INTENSITY, null)),
            muted = prefs.getBoolean(KEY_MUTED, false),
            room =
                RoomSize(
                    length = prefs.getFloat(KEY_LENGTH, 4.0f),
                    width = prefs.getFloat(KEY_WIDTH, 4.0f),
                    height = prefs.getFloat(KEY_HEIGHT, 2.6f),
                    floorOffset = prefs.getFloat(KEY_FLOOR, 0f),
                ),
            introSeen = prefs.getBoolean(KEY_INTRO, false),
            lastTaskS = prefs.getInt(KEY_LAST, 0),
        )

    fun save(s: Settings) {
        prefs.edit()
            .putString(KEY_LANG, s.language.tag)
            .putString(KEY_INTENSITY, s.intensity.tag)
            .putBoolean(KEY_MUTED, s.muted)
            .putFloat(KEY_LENGTH, s.room.length)
            .putFloat(KEY_WIDTH, s.room.width)
            .putFloat(KEY_HEIGHT, s.room.height)
            .putFloat(KEY_FLOOR, s.room.floorOffset)
            .putBoolean(KEY_INTRO, s.introSeen)
            .putInt(KEY_LAST, s.lastTaskS)
            .apply()
    }

    fun home(): Map<SourceId, HomeEntry> {
        val out = LinkedHashMap<SourceId, HomeEntry>()
        for (id in SourceId.entries) {
            val anchor = prefs.getString("home.${id.tag}.anchor", null) ?: continue
            out[id] = HomeEntry(anchor, prefs.getFloat("home.${id.tag}.yaw", 0f))
        }
        return out
    }

    fun saveHome(id: SourceId, entry: HomeEntry?) {
        val e = prefs.edit()
        if (entry == null) e.remove("home.${id.tag}.anchor").remove("home.${id.tag}.yaw") else e.putString("home.${id.tag}.anchor", entry.anchorId).putFloat("home.${id.tag}.yaw", entry.yaw)
        e.apply()
    }

    fun clearHome() {
        val e = prefs.edit()
        for (id in SourceId.entries) e.remove("home.${id.tag}.anchor").remove("home.${id.tag}.yaw")
        e.apply()
    }

    private companion object {
        const val FILE = "too_much"
        const val KEY_LANG = "language"
        const val KEY_INTENSITY = "intensity"
        const val KEY_MUTED = "muted"
        const val KEY_LENGTH = "room.length"
        const val KEY_WIDTH = "room.width"
        const val KEY_HEIGHT = "room.height"
        const val KEY_FLOOR = "room.floor"
        const val KEY_INTRO = "intro_seen"
        const val KEY_LAST = "last_task_s"
    }
}
