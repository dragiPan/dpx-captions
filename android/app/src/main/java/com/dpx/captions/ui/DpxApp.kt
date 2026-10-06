package com.dpx.captions.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.dpx.captions.LocalContainer
import com.dpx.captions.ui.editor.EditorScreen
import kotlinx.coroutines.launch

/**
 * Navigation is a single saved string - "home", "settings", "setup/<id>" or "editor/<id>" - which
 * survives rotation and process death without pulling in a navigation library for four screens.
 */
@Composable
fun DpxApp(sharedVideo: Uri?, onSharedConsumed: () -> Unit) {
    val container = LocalContainer.current
    var route by rememberSaveable { mutableStateOf("home") }
    var importProgress by remember { mutableStateOf<Float?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun import(uri: Uri) {
        if (importProgress != null) return
        scope.launch {
            importProgress = 0f
            try {
                val project = container.projects.import(uri, container.settings.state.value) { importProgress = it }
                route = "setup/${project.id}"
            } catch (e: Exception) {
                importError = e.message ?: "The file could not be read."
            } finally {
                importProgress = null
            }
        }
    }

    LaunchedEffect(sharedVideo) {
        if (sharedVideo != null) {
            import(sharedVideo)
            onSharedConsumed()
        }
    }

    BackHandler(enabled = route != "home") { route = "home" }

    when {
        route == "settings" -> SettingsScreen(onBack = { route = "home" })

        route.startsWith("setup/") -> {
            val id = route.removePrefix("setup/")
            SetupScreen(
                projectId = id,
                onBack = { route = "home" },
                onOpenEditor = { route = "editor/$id" },
            )
        }

        route.startsWith("editor/") -> {
            val id = route.removePrefix("editor/")
            EditorScreen(
                projectId = id,
                onClose = { route = "home" },
                onOpenSetup = { route = "setup/$id" },
            )
        }

        else -> HomeScreen(
            importProgress = importProgress,
            importError = importError,
            onDismissError = { importError = null },
            onImport = ::import,
            onOpenProject = { project ->
                route = if (project.cardCount > 0) "editor/${project.id}" else "setup/${project.id}"
            },
            onSettings = { route = "settings" },
        )
    }
}
