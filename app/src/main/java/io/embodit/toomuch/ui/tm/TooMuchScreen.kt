package io.embodit.toomuch.ui.tm

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.toomuch.platform.TmGraph
import io.embodit.toomuch.platform.TmLog
import io.embodit.toomuch.platform.audio.UiSfx
import io.embodit.toomuch.ui.tm.components.LocalTmResources
import io.embodit.toomuch.ui.tm.components.TmStage
import io.embodit.toomuch.ui.tm.components.localizedResources
import io.embodit.toomuch.ui.tm.debug.TmProbe
import io.embodit.toomuch.ui.tm.scene.TmFrameSystem
import io.embodit.toomuch.ui.tm.scene.TmScene

/**
 * The one screen of the app, in a Mixed Stage (Full Space). It wires the ViewModel (state machine +
 * engine), the [TmScene] (entities, sound, per-frame work, taps) and [TmStage] (the panels).
 */
@Composable
fun TooMuchScreen() {
    val context = LocalContext.current
    TmGraph.appContext = context.applicationContext
    val viewModel: TmViewModel = viewModel(factory = TmViewModel.Factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    remember(context) {
        TmProbe.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        true
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
                TmEffect.ExitApp -> {
                    TmLog.i("STAGE close requested")
                    runCatching { navigator.closeStage() }.onFailure { TmLog.e("STAGE close failed: $it", it) }
                }
                TmEffect.Snap -> sfx.snap(silent)
                TmEffect.Ding -> sfx.ding(silent)
            }
        }
    }

    // the in-app language switches at run time: panels read their copy from this local
    val resources = remember(state.language, context) { localizedResources(context, state.language) }
    CompositionLocalProvider(LocalTmResources provides resources) {
        TmStage(state = state, scene = scene, onEvent = viewModel::onEvent)
    }
}
