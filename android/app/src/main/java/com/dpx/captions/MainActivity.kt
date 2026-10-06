package com.dpx.captions

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.dpx.captions.ui.DpxApp
import com.dpx.captions.ui.DpxTheme

class MainActivity : ComponentActivity() {
    private val sharedVideo = mutableStateOf<Uri?>(null)

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        sharedVideo.value = videoFrom(intent)

        val container = (application as DpxApplication).container
        setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                DpxTheme {
                    Surface(
                        // Exposes test tags as resource ids, which is how UI automation drives the app.
                        modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
                    ) {
                        DpxApp(sharedVideo = sharedVideo.value, onSharedConsumed = { sharedVideo.value = null })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        videoFrom(intent)?.let { sharedVideo.value = it }
    }

    /** A video handed to the app from the gallery's Share sheet or an "Open with" action. */
    private fun videoFrom(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_SEND -> if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        Intent.ACTION_VIEW -> intent.data
        else -> null
    }
}
