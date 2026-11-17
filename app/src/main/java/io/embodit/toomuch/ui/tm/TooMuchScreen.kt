package io.embodit.toomuch.ui.tm

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.toomuch.platform.TmLog
import io.embodit.toomuch.platform.audio.UiSfx
import io.embodit.toomuch.ui.tm.components.LocalTmResources
import io.embodit.toomuch.ui.tm.components.TmStage
import io.embodit.toomuch.ui.tm.components.localizedResources
import io.embodit.toomuch.ui.tm.debug.TmProbe
import io.embodit.toomuch.ui.tm.scene.TmFrameSystem
import io.embodit.toomuch.ui.tm.scene.TmScene
import io.embodit.toomuch.ui.tm.window.exitApp

/**
 * The Stage side of the app (Mixed Stage, Full Space). The shared ViewModel (TmShared, process-level) holds the state
 * machine and the engine; [TmScene] owns entities, sound and per-frame work; [TmStage] declares the Stage's small
 * panels (source labels, the "open panel" chip). Every page is in the main system window (`TmWindow`), which this
 * screen opens (only with a level head, D9), recalls and closes.
 */
@Composable
fun TooMuchScreen() {
    val context = LocalContext.current
    val viewModel = remember(context) { TmShared.viewModel(context) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    remember(context) {
        TmProbe.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        true
    }

    // the main window opens with the Stage; its × closes it and the Stage's chip opens it again. A Planar window opens
    // on the line of sight and stays tilted if opened while looking down (D9): every open waits for a level head
    // (TmShared.askOpen, native-window-move §7 item 9); a minimised window is restored where it was
    fun openWindow(why: String) {
        if (TmShared.windowMinimised && navigator.restoreWindowContainer(TmShared.WINDOW_ID, TmShared.WINDOW_TAG)) {
            TmLog.i("WINDOW restore (was minimised) why=$why")
            return
        }
        TmShared.askOpen(why)
    }
    fun closeWindow() = navigator.closeWindowContainer(TmShared.WINDOW_ID, TmShared.WINDOW_TAG)
    DisposableEffect(navigator) {
        TmShared.opener = { navigator.openWindowContainer(TmShared.WINDOW_ID, TmShared.WINDOW_TAG) }
        onDispose {
            TmShared.cancelOpen()
            TmShared.opener = null
        }
    }
    LaunchedEffect(Unit) {
        viewModel.stageStarted()
        openWindow("stage")
    }
    // [Bring panel here] (settings): the window cannot be moved by code; closed and opened again it comes back in front
    // of the head. Open only after ON_DESTROY: an open right after a close is swallowed (native-window-move §3.5)
    var recallSeen by remember { mutableIntStateOf(state.recallSerial) }
    LaunchedEffect(state.recallSerial) {
        if (state.recallSerial == recallSeen) return@LaunchedEffect
        recallSeen = state.recallSerial
        if (TmShared.windowOpen) {
            TmShared.recallPending = true
            TmLog.i("WINDOW recall: close, open after ON_DESTROY")
            closeWindow()
        } else {
            openWindow("recall")
        }
    }
    var recallOpened by remember { mutableStateOf(false) }
    LaunchedEffect(TmShared.window) {
        if (!TmShared.recallPending) return@LaunchedEffect
        if (TmShared.window == "ON_DESTROY" && !recallOpened) {
            recallOpened = true
            TmLog.i("WINDOW recall: open")
            openWindow("recall")
        } else if (TmShared.windowOpen && recallOpened) {
            recallOpened = false
            TmShared.recallPending = false
        }
    }

    val scene = remember(viewModel) { TmScene(viewModel) }
    val sfx = remember(context) { UiSfx(context.applicationContext) }

    DisposableEffect(scene) {
        // the one per-frame system; every start has its stop in onDispose
        registerSystem<TmFrameSystem>()
        TmProbe.host =
            object : TmProbe.Host {
                override fun dispatch(event: TmEvent) = viewModel.onEvent(event)

                override fun uiState(): TmUiState = viewModel.state.value

                override fun scene(): TmProbe.SceneProbe = scene

                override fun pressControl(name: String): Boolean = TmProbe.click(name)

                override fun window(cmd: String) =
                    when (cmd) {
                        "open" -> openWindow("debug")
                        "close" -> {
                            closeWindow()
                            Unit
                        }
                        else -> viewModel.onEvent(TmEvent.Recall)
                    }
            }
        TmLog.i("STAGE enter")
        onDispose {
            TmProbe.host = null
            unregisterSystem<TmFrameSystem>()
            scene.detach()
            sfx.release()
            TmLog.i("STAGE disposed")
        }
    }

    // background: every source stops; foreground: back on the quiet card, no sound by itself (design A5)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> {
                        scene.pause()
                        viewModel.onEvent(TmEvent.WentBackground)
                    }
                    Lifecycle.Event.ON_START -> {
                        viewModel.onEvent(TmEvent.CameForeground)
                        scene.resume()
                    }
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(scene, state) { scene.render(state) }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            val silent = viewModel.state.value.muted || viewModel.state.value.screen == Screen.QUIET
            when (effect) {
                // template exit (D26): release sound / tracking / anchors and the window, close the Stage, remove the tasks, end
                TmEffect.ExitApp ->
                    exitApp(context, navigator) {
                        TmShared.cancelOpen()
                        scene.detach()
                        sfx.release()
                        closeWindow()
                    }
                TmEffect.Snap -> sfx.snap(silent)
                TmEffect.Ding -> sfx.ding(silent)
            }
        }
    }

    // the in-app language switches at run time: panels read their copy from this local
    val resources = remember(state.language, context) { localizedResources(context, state.language) }
    CompositionLocalProvider(LocalTmResources provides resources) {
        TmStage(state = state, scene = scene, onEvent = viewModel::onEvent, onOpenWindow = { openWindow("chip") })
    }
}
