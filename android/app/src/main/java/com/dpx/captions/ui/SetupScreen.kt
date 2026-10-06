package com.dpx.captions.ui

import android.Manifest
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.dpx.captions.LocalContainer
import com.dpx.captions.core.DownloadState
import com.dpx.captions.core.JobKind
import com.dpx.captions.core.JobState
import com.dpx.captions.model.CaptionFormatting
import com.dpx.captions.model.Density
import com.dpx.captions.model.TextCase
import com.dpx.captions.whisper.WhisperCatalog
import com.dpx.captions.whisper.WhisperLib
import com.dpx.captions.whisper.WhisperModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun SetupScreen(projectId: String, onBack: () -> Unit, onOpenEditor: () -> Unit) {
    val container = LocalContainer.current
    val project = remember(projectId) { container.projects.load(projectId) }
    if (project == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val settings by container.settings.state.collectAsState()
    var formatting by remember { mutableStateOf(project.formatting) }
    var modelId by remember { mutableStateOf(project.modelSize.takeIf { id -> WhisperCatalog.models.any { it.id == id } } ?: settings.modelId) }
    var confirmRegenerate by remember { mutableStateOf(false) }

    val jobState by container.jobs.state.collectAsState()
    val installed by container.downloads.installed.collectAsState()
    val downloads by container.downloads.state.collectAsState()

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val cpuOk = remember { runCatching { WhisperLib.cpuSupported() }.getOrDefault(false) }

    val running = (jobState as? JobState.Running)?.takeIf { it.projectId == projectId && it.kind == JobKind.GENERATE }
    val busyElsewhere = jobState is JobState.Running && running == null

    LaunchedEffect(jobState) {
        val state = jobState
        if (state is JobState.Finished && state.projectId == projectId && state.kind == JobKind.GENERATE) {
            container.jobs.acknowledge()
            onOpenEditor()
        }
    }

    fun generate() {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        container.settings.update { it.copy(formatting = formatting, modelId = modelId) }
        container.jobs.generate(projectId, modelId, formatting)
    }

    val selectedModel = WhisperCatalog.byId(modelId)
    val modelReady = modelId in installed

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text(project.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, modifier = Modifier.weight(1f))
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            VideoInfoCard(project.id, project.name, project.durationSeconds, project.width, project.height, project.fps)

            if (!cpuOk) {
                Surface(shape = MaterialTheme.shapes.medium, color = Color(0xFF3A1D1F)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Danger)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "This phone's processor lacks the instructions on-device transcription needs, so captions can't be generated here. You can still add them by hand.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            Section("Speech model") {
                for (model in WhisperCatalog.models) {
                    ModelRow(
                        model = model,
                        selected = model.id == modelId,
                        installed = model.id in installed,
                        download = downloads[model.id],
                        onSelect = { modelId = model.id },
                        onDownload = {
                            if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            container.downloads.start(model)
                        },
                        onCancel = { container.downloads.cancel(model) },
                        onDelete = { container.downloads.delete(model) },
                    )
                }
            }

            Section("Vocabulary & context") {
                OutlinedTextField(
                    value = formatting.vocabularyContext,
                    onValueChange = { formatting = formatting.copy(vocabularyContext = it) },
                    modifier = Modifier.fillMaxWidth().testTag("vocabulary"),
                    placeholder = { Text("Names, brands, gym terms the model should recognise…") },
                    minLines = 2,
                    maxLines = 4,
                )
            }

            FormatSection(formatting) { formatting = it }

            Spacer(Modifier.height(8.dp))
        }

        // Pinned action bar.
        Surface(color = Surface1, tonalElevation = 0.dp, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (running != null) {
                    Text(running.stage, style = MaterialTheme.typography.titleMedium)
                    val progress = running.progress
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = Accent, trackColor = Surface3)
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Accent, trackColor = Surface3)
                    }
                    OutlinedButton(onClick = { container.jobs.cancel() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Cancel")
                    }
                } else {
                    val hasCaptions = project.cards.isNotEmpty()
                    Button(
                        onClick = { if (hasCaptions) confirmRegenerate = true else generate() },
                        enabled = modelReady && cpuOk && !busyElsewhere,
                        modifier = Modifier.fillMaxWidth().height(54.dp).testTag("generate"),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text(
                            when {
                                busyElsewhere -> "Another job is running"
                                !modelReady -> "Download ${selectedModel.label} first"
                                hasCaptions -> "Regenerate captions"
                                else -> "Generate captions"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    TextButton(onClick = onOpenEditor, modifier = Modifier.fillMaxWidth().testTag("skip")) {
                        Text(if (hasCaptions) "Back to the editor" else "Skip — write captions by hand")
                    }
                }
            }
        }
    }

    if (confirmRegenerate) {
        AlertDialog(
            onDismissRequest = { confirmRegenerate = false },
            title = { Text("Replace your captions?") },
            text = { Text("Regenerating discards the ${project.cards.size} captions in this project, including any edits you made.") },
            confirmButton = {
                TextButton(onClick = { confirmRegenerate = false; generate() }) { Text("Replace", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmRegenerate = false }) { Text("Keep them") } },
        )
    }

    (jobState as? JobState.Failed)?.takeIf { it.projectId == projectId && it.kind == JobKind.GENERATE }?.let { failed ->
        AlertDialog(
            onDismissRequest = { container.jobs.acknowledge() },
            title = { Text("Transcription failed") },
            text = { Text(failed.message) },
            confirmButton = { TextButton(onClick = { container.jobs.acknowledge() }) { Text("OK") } },
        )
    }
}

@Composable
private fun VideoInfoCard(id: String, name: String, duration: Double, width: Int, height: Int, fps: Double) {
    val container = LocalContainer.current
    val thumbnail by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, id) {
        value = withContext(Dispatchers.IO) {
            container.projects.thumbnail(id).takeIf { it.exists() }
                ?.let { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() }
        }
    }
    Surface(shape = MaterialTheme.shapes.medium, color = Surface1) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 72.dp, height = 96.dp).clip(RoundedCornerShape(10.dp)).background(Surface3)) {
                thumbnail?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
            Spacer(Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                // Length and resolution up front: a truncated or oddly-sized export is obvious at a glance.
                Text(formatTime(duration, tenths = true), style = MaterialTheme.typography.titleLarge, color = Accent)
                Text(
                    "$width×$height  •  ${fps.roundToInt()} fps",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceDim,
                )
            }
        }
    }
}

@Composable
private fun ModelRow(
    model: WhisperModel,
    selected: Boolean,
    installed: Boolean,
    download: DownloadState?,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.5.dp, if (selected) Accent else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .clickable(onClick = onSelect)
            .padding(12.dp)
            .testTag("model_${model.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(20.dp).border(2.dp, if (selected) Accent else OnSurfaceDim, CircleShape).padding(4.dp),
            ) {
                if (selected) Box(Modifier.fillMaxSize().background(Accent, CircleShape))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (model.recommended) {
                    Text(
                        "RECOMMENDED",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.background(Accent, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Text(model.label, style = MaterialTheme.typography.titleMedium)
                Text(model.hint, style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
            }
            Spacer(Modifier.width(8.dp))
            when {
                installed -> {
                    Icon(Icons.Default.Check, contentDescription = "Downloaded", tint = Waveform)
                    IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete model", tint = OnSurfaceDim) }
                }
                download is DownloadState.Downloading -> {
                    IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = "Pause download") }
                }
                else -> {
                    OutlinedButton(onClick = onDownload, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(formatBytes(model.sizeBytes))
                    }
                }
            }
        }
        if (download is DownloadState.Downloading) {
            LinearProgressIndicator(progress = { download.fraction }, modifier = Modifier.fillMaxWidth(), color = Accent, trackColor = Surface3)
            Text(
                "${formatBytes(download.downloaded)} of ${formatBytes(download.total)}",
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceDim,
            )
        }
        if (download is DownloadState.Failed) {
            Text("${download.message} — tap download to resume", style = MaterialTheme.typography.bodySmall, color = Danger)
        }
    }
}

@Composable
private fun FormatSection(formatting: CaptionFormatting, onChange: (CaptionFormatting) -> Unit) {
    Section("Caption format") {
        Text("Text density", style = MaterialTheme.typography.bodyMedium)
        ChoiceRow(
            options = listOf(
                Density.SINGLE_WORD to "Word",
                Density.STANDARD to "Standard",
                Density.MORE to "More",
                Density.CUSTOM to "Custom",
            ),
            selected = formatting.density,
            onSelect = { onChange(formatting.copy(density = it)) },
        )

        if (formatting.density == Density.CUSTOM) {
            LabeledSlider(
                label = "Characters per line",
                valueText = formatting.maxCharsPerLine.toString(),
                value = formatting.maxCharsPerLine.toFloat(),
                range = 5f..40f,
                steps = 34,
                onChange = { onChange(formatting.copy(maxCharsPerLine = it.roundToInt())) },
            )
            Text("Lines", style = MaterialTheme.typography.bodyMedium)
            ChoiceRow(
                options = listOf(1 to "1", 2 to "2", 3 to "3"),
                selected = formatting.lineCount,
                onSelect = { onChange(formatting.copy(lineCount = it)) },
            )
        }

        Text("Text case", style = MaterialTheme.typography.bodyMedium)
        ChoiceRow(
            options = listOf(
                TextCase.UPPERCASE to "ABC",
                TextCase.LOWERCASE to "abc",
                TextCase.TITLE_CASE to "Abc",
                TextCase.NORMAL to "Normal",
            ),
            selected = formatting.textCase,
            onSelect = { onChange(formatting.copy(textCase = it)) },
        )

        SwitchRow("Remove punctuation", formatting.removePunctuation) { onChange(formatting.copy(removePunctuation = it)) }
        SwitchRow("Censor words", formatting.censorWords) { onChange(formatting.copy(censorWords = it)) }

        if (formatting.censorWords) {
            OutlinedTextField(
                value = formatting.censorWordList.joinToString(", "),
                onValueChange = { text ->
                    onChange(formatting.copy(censorWordList = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }))
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Words to censor, separated by commas") },
                singleLine = true,
            )
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = Accent,
            ),
        )
    }
}
