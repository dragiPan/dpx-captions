package com.dpx.captions

import android.app.Application
import androidx.compose.runtime.staticCompositionLocalOf
import com.dpx.captions.core.AppContainer

class DpxApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
