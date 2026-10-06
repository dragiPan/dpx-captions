package com.dpx.captions.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dpx.captions.BuildConfig
import com.dpx.captions.LocalContainer
import com.dpx.captions.whisper.WhisperCatalog

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val settings by container.settings.state.collectAsState()
    val installed by container.downloads.installed.collectAsState()

    var fontVersion by remember { mutableIntStateOf(0) }
    var fontMessage by remember { mutableStateOf<String?>(null) }
    var pendingFontDelete by remember { mutableStateOf<String?>(null) }

    val pickFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = runCatching { container.fonts.importFont(uri) }.getOrNull()
            fontMessage = if (name != null) "Added “$name”" else "That file isn't a usable .ttf or .otf font."
            fontVersion++
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Section("Transcription") {
                Text("Accuracy", style = MaterialTheme.typography.bodyMedium)
                ChoiceRow(
                    options = listOf(1 to "Fast", 5 to "Accurate"),
                    selected = if (settings.beamSize > 1) 5 else 1,
                    onSelect = { size -> container.settings.update { it.copy(beamSize = size) } },
                )
                Text(
                    "Accurate searches several wordings per phrase: better Serbian, a bit slower.",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim,
                )

                Text("CPU threads", style = MaterialTheme.typography.bodyMedium)
                ChoiceRow(
                    options = listOf(0 to "Auto", 2 to "2", 4 to "4", 6 to "6", 8 to "8"),
                    selected = settings.threads,
                    onSelect = { n -> container.settings.update { it.copy(threads = n) } },
                )
                Text(
                    "More threads is not always faster: using the slow efficiency cores can make it worse.",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim,
                )
            }

            Section("Fonts") {
                // Re-read when a font is added or removed.
                val names = remember(fontVersion) { container.fonts.names() }
                for (name in names) {
                    val family = remember(name, fontVersion) { FontFamily(container.fonts.resolve(name, true).typeface) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
                            Text("KREATIN 101 šćčžđ", fontFamily = family, style = MaterialTheme.typography.titleLarge)
                        }
                        if (container.fonts.isImported(name)) {
                            IconButton(onClick = { pendingFontDelete = name }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove font", tint = OnSurfaceDim)
                            }
                        }
                    }
                }
                OutlinedButton(
                    onClick = { pickFont.launch(arrayOf("font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream")) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Import a font file (.ttf / .otf)") }
                Text(
                    "Copy a font such as Franklin Gothic Demi to the phone, then import it here. Presets that name it will pick it up automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim,
                )
                fontMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Accent) }
            }

            Section("Downloaded models") {
                val downloaded = WhisperCatalog.models.filter { it.id in installed }
                if (downloaded.isEmpty()) {
                    Text("None yet. Models are downloaded from the project setup screen.", style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim)
                }
                for (model in downloaded) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(model.label, style = MaterialTheme.typography.bodyLarge)
                            Text(formatBytes(model.sizeBytes), style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
                        }
                        IconButton(onClick = { container.downloads.delete(model) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete ${model.label}", tint = OnSurfaceDim)
                        }
                    }
                }
            }

            Section("About") {
                Text("DPX Captions ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Speech recognition by whisper.cpp (MIT). Bundled fonts are licensed under the SIL Open Font License. " +
                        "Video playback and export by AndroidX Media3 (Apache 2.0).",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    pendingFontDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingFontDelete = null },
            title = { Text("Remove “$name”?") },
            text = { Text("Captions that use it will fall back to the default font.") },
            confirmButton = {
                TextButton(onClick = {
                    container.fonts.removeImported(name)
                    fontVersion++
                    pendingFontDelete = null
                }) { Text("Remove", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { pendingFontDelete = null }) { Text("Cancel") } },
        )
    }
}
