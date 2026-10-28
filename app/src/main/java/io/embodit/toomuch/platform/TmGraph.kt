package io.embodit.toomuch.platform

import android.content.Context
import io.embodit.toomuch.data.SettingsStore
import io.embodit.toomuch.platform.anchor.WorldAnchorGateway
import io.embodit.toomuch.platform.input.ControllerInput
import io.embodit.toomuch.platform.tracking.HeadTracker

/**
 * Process-wide wiring of the platform services. The Stage starts and stops the trackers (every start
 * has a stop: see `TmScene.attach` / `detach`); the ViewModel sees the anchors and the settings store.
 */
object TmGraph {
    val head = HeadTracker()
    val controller = ControllerInput()
    val anchors = WorldAnchorGateway()

    @Volatile var appContext: Context? = null

    @Volatile private var settings: SettingsStore? = null

    fun settings(context: Context): SettingsStore =
        settings ?: synchronized(this) { settings ?: SettingsStore(context).also { settings = it } }
}
