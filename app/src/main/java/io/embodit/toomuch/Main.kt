package io.embodit.toomuch

import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.toomuch.ui.theme.TooMuchTheme
import io.embodit.toomuch.ui.tm.TmShared
import io.embodit.toomuch.ui.tm.TooMuchScreen
import io.embodit.toomuch.ui.tm.window.TmWindow

/**
 * App entry. One Mixed Stage (AndroidManifest: `pico.spatial.stage.style=1`) holds the room, the sound cards, the
 * tray and the head-locked layer; every 2D page is in one Planar system window (v0.1.1, factory native-window-move):
 * native TabBar, the system's move / close controls. The Stage opens the window once the head is level (D9).
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage {
            TooMuchTheme {
                TooMuchScreen()
            }
        }
        WindowContainer(TmShared.WINDOW_ID, form = Form.Planar, defaultSize = WindowContainerSize(width = TmShared.WINDOW_W_DP.dp, height = TmShared.WINDOW_H_DP.dp)) {
            TooMuchTheme {
                TmWindow()
            }
        }
    }
