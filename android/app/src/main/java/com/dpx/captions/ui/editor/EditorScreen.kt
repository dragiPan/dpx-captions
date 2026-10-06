package com.dpx.captions.ui.editor

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dpx.captions.LocalContainer
import com.dpx.captions.model.Aspect
import com.dpx.captions.ui.Accent
import com.dpx.captions.ui.ChoiceRow
import com.dpx.captions.ui.OnSurfaceDim
import com.dpx.captions.ui.Surface0
import com.dpx.captions.ui.Surface1
import com.dpx.captions.ui.Surface2
import com.dpx.captions.ui.ToolButton
import com.dpx.captions.ui.formatTime
import kotlinx.coroutines.flow.collectLatest

private val StylePanelHeight = 340.dp

@Composable
fun EditorScreen(projectId: String, onClose: () -> Unit, onOpenSetup: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val initial = remember(projectId) { container.projects.load(projectId) }
    if (initial == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    val state = remember(projectId) { EditorState(container, initial, scope) }
    val controller = remember(projectId) { PlayerController(context, initial.videoPath) }
    LaunchedEffect(state) { state.loadPeaks() }

    DisposableEffect(controller) {
        onDispose {
            controller.release()
            state.saveNow()
        }
    }

    // Playback and edits shouldn't outlive the screen being visible.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                controller.pause()
                state.saveNow()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // While playing, advance the playhead once per rendered frame.
    LaunchedEffect(controller) {
        snapshotFlow { controller.isPlaying }.collectLatest { playing ->
            if (playing) while (true) androidx.compose.runtime.withFrameNanos { controller.tick() }
        }
    }

    val density = LocalDensity.current
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    // About eight seconds across the screen to start with, which suits short-form captions.
    var pps by remember { mutableFloatStateOf(screenWidthPx / 8f) }

    var showStyle by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showFind by remember { mutableStateOf(false) }
    var showAspect by remember { mutableStateOf(false) }
    var confirmRegroup by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var guides by remember { mutableStateOf(container.settings.state.value.guides) }

    val project = state.project
    val selected = state.selected

    BackHandler {
        when {
            showStyle -> showStyle = false
            selected != null -> state.select(null)
            else -> onClose()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Surface0)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        TopBar(
            title = project.name,
            canUndo = state.canUndo,
            canRedo = state.canRedo,
            onBack = onClose,
            onUndo = state::undo,
            onRedo = state::redo,
            onExport = {
                controller.pause()
                showExport = true
            },
        )

        PreviewPane(
            controller = controller,
            state = state,
            guides = guides,
            onMoveCaption = { x, y -> state.previewStyle(state.style.withPosition(x, y)) },
            onMoveCaptionEnd = state::commitStyle,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        TransportRow(
            controller = controller,
            durationSeconds = project.durationSeconds,
            aspect = project.aspect,
            guides = guides,
            onZoomOut = { pps = (pps / 1.4f).coerceIn(MIN_PPS, MAX_PPS) },
            onZoomIn = { pps = (pps * 1.4f).coerceIn(MIN_PPS, MAX_PPS) },
            onAspect = { showAspect = true },
            onGuides = {
                guides = !guides
                container.settings.update { it.copy(guides = guides) }
            },
        )

        if (showStyle) {
            StylePanel(
                style = state.style,
                fonts = container.fonts,
                onPreview = state::previewStyle,
                onCommit = state::commitStyle,
                onSet = state::setStyle,
                onClose = { showStyle = false },
                modifier = Modifier.height(StylePanelHeight),
            )
        } else {
            Box {
                TimelineView(
                    cards = state.cards,
                    selected = selected,
                    durationSeconds = project.durationSeconds,
                    peaks = state.peaks,
                    positionSeconds = { controller.positionSeconds },
                    pixelsPerSecond = pps,
                    onZoom = { pps = it },
                    onScrub = { seconds, finished ->
                        if (controller.isPlaying) controller.pause()
                        controller.seekTo((seconds * 1000).toLong(), scrubbing = !finished)
                    },
                    onSelect = state::select,
                    onCommit = { cards, index -> state.commitCards(cards, index) },
                    modifier = Modifier.testTag("timeline"),
                )
                if (state.cards.isEmpty()) {
                    Text(
                        "No captions yet. Tap Add to put one at the playhead.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceDim,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
                    )
                }
            }

            ToolBar(
                hasSelection = selected != null,
                canRegroup = state.canRegroup,
                onEdit = { selected?.let { editing = it } },
                onSplit = {
                    if (!state.splitAt(controller.positionSeconds)) {
                        Toast.makeText(context, "Move the playhead inside the caption first", Toast.LENGTH_SHORT).show()
                    }
                },
                onDelete = { selected?.let(state::deleteAt) },
                onDuplicate = {
                    if (selected?.let(state::duplicate) != true) {
                        Toast.makeText(context, "No room after this caption", Toast.LENGTH_SHORT).show()
                    }
                },
                onMerge = {
                    if (selected?.let(state::mergeWithNext) != true) {
                        Toast.makeText(context, "There is no caption after this one", Toast.LENGTH_SHORT).show()
                    }
                },
                onDone = { state.select(null) },
                onAdd = {
                    controller.pause()
                    state.addAt(controller.positionSeconds)?.let { editing = it }
                        ?: Toast.makeText(context, "No room to add a caption here", Toast.LENGTH_SHORT).show()
                },
                onStyle = { showStyle = true },
                onFind = { showFind = true },
                onAspect = { showAspect = true },
                onRegroup = { confirmRegroup = true },
                onRegenerate = onOpenSetup,
            )
        }
    }

    editing?.let { index ->
        val card = state.cards.getOrNull(index)
        if (card == null) {
            editing = null
        } else {
            EditTextDialog(
                initial = card.text,
                onSave = { text ->
                    state.editText(index, text)
                    editing = null
                },
                onDismiss = { editing = null },
            )
        }
    }

    if (showFind) {
        FindReplaceDialog(
            onReplace = { find, replacement, matchCase, wholeWord -> state.findReplace(find, replacement, matchCase, wholeWord) },
            onDismiss = { showFind = false },
        )
    }

    if (showAspect) {
        AlertDialog(
            onDismissRequest = { showAspect = false },
            title = { Text("Aspect ratio") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ChoiceRow(
                        options = Aspect.entries.map { it to it.label },
                        selected = project.aspect,
                        onSelect = state::setAspect,
                    )
                    Text(
                        "The video is fitted inside this frame with black bars if its shape differs. Captions are placed on the frame, and the export uses the same one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showAspect = false }) { Text("Done") } },
        )
    }

    if (confirmRegroup) {
        ConfirmDialog(
            title = "Regroup captions?",
            message = "Captions are rebuilt from the transcript using the format chosen on the setup screen. Edits you made to caption text and timing are discarded (you can undo this).",
            confirmLabel = "Regroup",
            destructive = true,
            onConfirm = {
                confirmRegroup = false
                state.regroup()
            },
            onDismiss = { confirmRegroup = false },
        )
    }

    if (showExport) {
        ExportSheet(state = state, onDismiss = { showExport = false })
    }
}

@Composable
private fun TopBar(
    title: String,
    canUndo: Boolean,
    canRedo: Boolean,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onExport: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.testTag("undo")) {
            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", tint = if (canUndo) Color.White else Color(0xFF555C68))
        }
        IconButton(onClick = onRedo, enabled = canRedo, modifier = Modifier.testTag("redo")) {
            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo", tint = if (canRedo) Color.White else Color(0xFF555C68))
        }
        Spacer(Modifier.width(4.dp))
        Button(
            onClick = onExport,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.height(38.dp).testTag("export"),
        ) { Text("Export", style = MaterialTheme.typography.labelLarge) }
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun TransportRow(
    controller: PlayerController,
    durationSeconds: Double,
    aspect: Aspect,
    guides: Boolean,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    onAspect: () -> Unit,
    onGuides: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(Surface0).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Accent)
                .clickable { controller.togglePlay() }
                .testTag("play"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (controller.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (controller.isPlaying) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            formatTime(controller.positionSeconds, tenths = true),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("time"),
        )
        Text(" / ${formatTime(durationSeconds, tenths = true)}", style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim)

        Spacer(Modifier.weight(1f))

        Box(
            Modifier
                .height(32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Surface2)
                .clickable(onClick = onAspect)
                .padding(horizontal = 12.dp)
                .testTag("aspect"),
            contentAlignment = Alignment.Center,
        ) { Text(aspect.label, style = MaterialTheme.typography.labelLarge) }

        IconButton(onClick = onGuides, modifier = Modifier.testTag("guides")) {
            Icon(Icons.Default.GridOn, contentDescription = "Safe-area guides", tint = if (guides) Accent else Color.White)
        }
        IconButton(onClick = onZoomOut, modifier = Modifier.testTag("zoom_out")) {
            Icon(Icons.Default.ZoomOut, contentDescription = "Zoom timeline out")
        }
        IconButton(onClick = onZoomIn, modifier = Modifier.testTag("zoom_in")) {
            Icon(Icons.Default.ZoomIn, contentDescription = "Zoom timeline in")
        }
    }
}

@Composable
private fun ToolBar(
    hasSelection: Boolean,
    canRegroup: Boolean,
    onEdit: () -> Unit,
    onSplit: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onMerge: () -> Unit,
    onDone: () -> Unit,
    onAdd: () -> Unit,
    onStyle: () -> Unit,
    onFind: () -> Unit,
    onAspect: () -> Unit,
    onRegroup: () -> Unit,
    onRegenerate: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Surface1)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (hasSelection) {
            ToolButton(Icons.Default.Edit, "Edit", onEdit, testTag = "tool_edit")
            ToolButton(Icons.Default.ContentCut, "Split", onSplit, testTag = "tool_split")
            ToolButton(Icons.Default.Delete, "Delete", onDelete, testTag = "tool_delete")
            ToolButton(Icons.Default.ContentCopy, "Duplicate", onDuplicate, testTag = "tool_duplicate")
            ToolButton(Icons.Default.CallMerge, "Merge", onMerge, testTag = "tool_merge")
            ToolButton(Icons.Default.Palette, "Style", onStyle, testTag = "tool_style")
            ToolButton(Icons.Default.Check, "Done", onDone, testTag = "tool_done")
        } else {
            ToolButton(Icons.Default.Add, "Add", onAdd, testTag = "tool_add")
            ToolButton(Icons.Default.Palette, "Style", onStyle, testTag = "tool_style")
            ToolButton(Icons.Default.FindReplace, "Replace", onFind, testTag = "tool_find")
            ToolButton(Icons.Default.AspectRatio, "Aspect", onAspect, testTag = "tool_aspect")
            if (canRegroup) ToolButton(Icons.Default.AutoFixHigh, "Regroup", onRegroup, testTag = "tool_regroup")
            ToolButton(Icons.Default.Refresh, "Redo AI", onRegenerate, testTag = "tool_regenerate")
        }
    }
}
