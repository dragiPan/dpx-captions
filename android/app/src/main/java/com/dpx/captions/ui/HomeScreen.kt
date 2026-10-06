package com.dpx.captions.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dpx.captions.LocalContainer
import com.dpx.captions.core.JobState
import com.dpx.captions.core.ProjectSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(
    importProgress: Float?,
    importError: String?,
    onDismissError: () -> Unit,
    onImport: (Uri) -> Unit,
    onOpenProject: (ProjectSummary) -> Unit,
    onSettings: () -> Unit,
) {
    val container = LocalContainer.current
    var projects by remember { mutableStateOf(container.projects.list()) }
    var pendingDelete by remember { mutableStateOf<ProjectSummary?>(null) }
    val jobState by container.jobs.state.collectAsState()

    // Reload whenever a job finishes, since generating captions changes a project's card count.
    LaunchedEffect(jobState, importProgress) {
        projects = withContext(Dispatchers.IO) { container.projects.list() }
    }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(onImport)
    }
    val browseFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImport)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("DPX Captions", style = MaterialTheme.typography.headlineMedium)
                Text("Serbian captions for short video", style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim)
            }
            IconButton(onClick = onSettings, modifier = Modifier.testTag("settings")) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        }

        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                },
                enabled = importProgress == null,
                modifier = Modifier.fillMaxWidth().height(56.dp).testTag("new_project"),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("New project", style = MaterialTheme.typography.titleMedium)
            }
            OutlinedButton(
                onClick = { browseFiles.launch(arrayOf("video/*")) },
                enabled = importProgress == null,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Browse files")
            }

            if (importProgress != null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Importing video…", style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim)
                    LinearProgressIndicator(
                        progress = { importProgress },
                        modifier = Modifier.fillMaxWidth(),
                        color = Accent,
                        trackColor = Surface3,
                    )
                }
            }

            val running = jobState as? JobState.Running
            if (running != null) {
                Surface(shape = MaterialTheme.shapes.medium, color = Surface2) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(running.stage, style = MaterialTheme.typography.titleMedium)
                        val progress = running.progress
                        if (progress != null) {
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = Accent, trackColor = Surface3)
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Accent, trackColor = Surface3)
                        }
                    }
                }
            }
        }

        Text(
            "RECENT PROJECTS",
            style = MaterialTheme.typography.labelMedium,
            color = OnSurfaceDim,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
        )

        if (projects.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.TopCenter) {
                Text(
                    "No projects yet. Pick a video to start captioning.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceDim,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().testTag("project_list"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(projects, key = { it.id }) { project ->
                    ProjectCard(project, onClick = { onOpenProject(project) }, onDelete = { pendingDelete = project })
                }
            }
        }
    }

    pendingDelete?.let { project ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete “${project.name}”?") },
            text = { Text("This removes the project, its captions and the video copy stored in the app. The original in your gallery is untouched.") },
            confirmButton = {
                TextButton(onClick = {
                    container.projects.delete(project.id)
                    projects = container.projects.list()
                    pendingDelete = null
                }) { Text("Delete", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }

    importError?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text("Couldn't import that video") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = onDismissError) { Text("OK") } },
        )
    }
}

@Composable
private fun ProjectCard(project: ProjectSummary, onClick: () -> Unit, onDelete: () -> Unit) {
    val container = LocalContainer.current
    val thumbnail by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, project.id, project.modifiedAt) {
        value = withContext(Dispatchers.IO) {
            container.projects.thumbnail(project.id).takeIf { it.exists() }
                ?.let { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() }
        }
    }
    var menu by remember { mutableStateOf(false) }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = Surface1,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(width = 64.dp, height = 84.dp).clip(RoundedCornerShape(10.dp)).background(Surface3),
                contentAlignment = Alignment.Center,
            ) {
                thumbnail?.let {
                    Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(project.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${formatTime(project.durationSeconds)}  •  ${project.width}×${project.height}",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim,
                )
                Text(
                    if (project.cardCount > 0) "${project.cardCount} captions" else "No captions yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (project.cardCount > 0) Accent else OnSurfaceDim,
                )
                Text(
                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(project.modifiedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceDim,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Delete", color = Danger) },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Danger) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}
