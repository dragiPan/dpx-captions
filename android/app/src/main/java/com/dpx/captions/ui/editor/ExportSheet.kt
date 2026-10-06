package com.dpx.captions.ui.editor

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.dpx.captions.LocalContainer
import com.dpx.captions.core.ExportOptions
import com.dpx.captions.core.JobKind
import com.dpx.captions.core.JobState
import com.dpx.captions.model.Aspect
import com.dpx.captions.ui.Accent
import com.dpx.captions.ui.ChoiceRow
import com.dpx.captions.ui.Danger
import com.dpx.captions.ui.OnSurfaceDim
import com.dpx.captions.ui.Surface3
import com.dpx.captions.ui.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(state: EditorState, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val project = state.project

    var maxLongSide by remember { mutableStateOf<Int?>(null) }
    var quality by remember { mutableStateOf(1f) }
    var savedToGallery by remember { mutableStateOf(false) }

    val job by container.jobs.state.collectAsState()
    val running = (job as? JobState.Running)?.takeIf { it.projectId == project.id && it.kind == JobKind.EXPORT }
    val finished = (job as? JobState.Finished)?.takeIf { it.projectId == project.id && it.kind == JobKind.EXPORT }
    val failed = (job as? JobState.Failed)?.takeIf { it.projectId == project.id && it.kind == JobKind.EXPORT }
    val busyElsewhere = job is JobState.Running && running == null

    val options = ExportOptions(project.aspect, maxLongSide, quality)
    val (outW, outH) = remember(project.aspect, maxLongSide, project.width, project.height) {
        container.exporter.outputSize(project.width, project.height, options)
    }
    val estimate by produceState<Long?>(null, outW, outH, quality) {
        value = withContext(Dispatchers.IO) {
            val bitrate = container.exporter.targetBitrate(project, outW, outH, quality)
            ((bitrate + 128_000L) * project.durationSeconds / 8).toLong()
        }
    }

    // A finished export lives in the cache; leaving the sheet should not leave a stale "done" state behind.
    LaunchedEffect(Unit) { if (job is JobState.Failed || job is JobState.Finished) container.jobs.acknowledge() }

    ModalBottomSheet(
        onDismissRequest = { if (running == null) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = com.dpx.captions.ui.Surface1,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Export video", style = MaterialTheme.typography.titleLarge)

            when {
                running != null -> {
                    Text(running.stage, style = MaterialTheme.typography.titleMedium)
                    val progress = running.progress
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = Accent, trackColor = Surface3)
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Accent, trackColor = Surface3)
                    }
                    Text(
                        "You can leave the app; the export keeps running and shows its progress in a notification.",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim,
                    )
                    OutlinedButton(onClick = { container.jobs.cancel() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel export") }
                }

                finished?.outputPath != null -> {
                    val file = File(finished.outputPath)
                    Text("Done — ${formatBytes(file.length())}", style = MaterialTheme.typography.titleMedium, color = Accent)
                    Button(
                        onClick = {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { saveToGallery(context, file, project.name) }
                                savedToGallery = ok
                                Toast.makeText(context, if (ok) "Saved to Movies/DPX Captions" else "Couldn't save the video", Toast.LENGTH_LONG).show()
                            }
                        },
                        enabled = !savedToGallery,
                        modifier = Modifier.fillMaxWidth().height(52.dp).testTag("save_gallery"),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text(if (savedToGallery) "Saved to gallery" else "Save to gallery") }
                    OutlinedButton(
                        onClick = { share(context, file) },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("Share…") }
                    TextButton(onClick = { container.jobs.acknowledge(); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Close") }
                }

                else -> {
                    failed?.let {
                        Text("Export failed: ${it.message}", style = MaterialTheme.typography.bodyMedium, color = Danger)
                    }

                    Text("Aspect ratio", style = MaterialTheme.typography.bodyMedium)
                    ChoiceRow(
                        options = Aspect.entries.map { it to it.label },
                        selected = project.aspect,
                        onSelect = state::setAspect,
                    )

                    Text("Resolution", style = MaterialTheme.typography.bodyMedium)
                    ChoiceRow(
                        options = listOf<Pair<Int?, String>>(null to "Original", 1920 to "1080p", 1280 to "720p"),
                        selected = maxLongSide,
                        onSelect = { maxLongSide = it },
                    )

                    Text("Quality", style = MaterialTheme.typography.bodyMedium)
                    ChoiceRow(
                        options = listOf(1.5f to "High", 1f to "Match source", 0.6f to "Small"),
                        selected = quality,
                        onSelect = { quality = it },
                    )

                    Text(
                        "Output: $outW×$outH" + (estimate?.let { "  •  about ${formatBytes(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceDim,
                    )

                    Button(
                        onClick = {
                            scope.launch {
                                // The export job reads the project from disk, so write the latest edits first.
                                withContext(Dispatchers.IO) { container.projects.save(state.project) }
                                container.jobs.export(project.id, options)
                            }
                        },
                        enabled = !busyElsewhere && project.cards.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().height(54.dp).testTag("start_export"),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text(
                            when {
                                busyElsewhere -> "Another job is running"
                                project.cards.isEmpty() -> "Add captions first"
                                else -> "Export"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

private fun saveToGallery(context: Context, file: File, name: String): Boolean = runCatching {
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, "${name}_captioned.mp4")
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/DPX Captions")
        put(MediaStore.Video.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
    values.clear()
    values.put(MediaStore.Video.Media.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
    true
}.getOrDefault(false)

private fun share(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share video").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
