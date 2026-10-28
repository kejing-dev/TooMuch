package io.embodit.toomuch.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.toomuch.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TmGraph.appContext = applicationContext
        launch(::mainApp)
    }
}
