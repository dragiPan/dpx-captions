package com.dpx.captions.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dpx.captions.core.Presets
import com.dpx.captions.model.AnimationStyle
import com.dpx.captions.model.Rgb
import com.dpx.captions.render.FontRegistry
import com.dpx.captions.ui.Accent
import com.dpx.captions.ui.LabeledSlider
import com.dpx.captions.ui.OnSurfaceDim
import com.dpx.captions.ui.Surface1
import com.dpx.captions.ui.Surface2
import com.dpx.captions.ui.Surface3
import com.dpx.captions.ui.SwitchRow
import kotlin.math.roundToInt

private enum class StyleTab(val label: String) {
    FONT("Font"), SIZE("Size"), COLORS("Colors"), OUTLINE("Outline"), MOTION("Motion"), PRESETS("Presets")
}

/**
 * Edits the caption style while the preview stays visible above it. Sliders report live changes through
 * [onPreview] and a single [onCommit] when the finger lifts, so a drag is one undo step, not hundreds.
 */
@Composable
fun StylePanel(
    style: AnimationStyle,
    fonts: FontRegistry,
    onPreview: (AnimationStyle) -> Unit,
    onCommit: () -> Unit,
    onSet: (AnimationStyle) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(StyleTab.FONT) }

    Column(modifier.fillMaxWidth().background(Surface1)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Caption style", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose, modifier = Modifier.testTag("style_done")) { Text("Done") }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (t in StyleTab.entries) {
                val selected = t == tab
                Box(
                    Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (selected) Accent else Surface3)
                        .clickable { tab = t }
                        .padding(horizontal = 16.dp)
                        .testTag("tab_${t.name.lowercase()}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        t.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (tab) {
                StyleTab.FONT -> FontTab(style, fonts, onSet)
                StyleTab.SIZE -> SizeTab(style, onPreview, onCommit, onSet)
                StyleTab.COLORS -> ColorsTab(style, onPreview, onCommit)
                StyleTab.OUTLINE -> OutlineTab(style, onPreview, onCommit, onSet)
                StyleTab.MOTION -> MotionTab(style, onPreview, onCommit, onSet)
                StyleTab.PRESETS -> PresetsTab(style, fonts, onSet)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun FontTab(style: AnimationStyle, fonts: FontRegistry, onSet: (AnimationStyle) -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }

    val pickFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = runCatching { fonts.importFont(uri) }.getOrNull()
            if (name != null) {
                onSet(style.copy(font = name))
                message = "Added “$name”"
            } else {
                message = "That file isn't a usable .ttf or .otf font."
            }
            version++
        }
    }

    SwitchRow("Bold", style.bold) { onSet(style.copy(bold = it, styleName = if (it) "Bold" else "Regular")) }

    if (!fonts.has(style.font)) {
        Text(
            "“${style.font}” isn't installed, so “${fonts.defaultName}” is being used instead. Import the font file to use it.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFE0A030),
        )
    }

    val names = remember(version) { fonts.names() }
    for (name in names) {
        val selected = name.equals(style.font, ignoreCase = true)
        val family = remember(name, version, style.bold) { FontFamily(fonts.resolve(name, style.bold).typeface) }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (selected) Surface3 else Surface2)
                .border(1.5.dp, if (selected) Accent else Color.Transparent, RoundedCornerShape(12.dp))
                .clickable { onSet(style.copy(font = name)) }
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag("font_$name"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.labelSmall, color = OnSurfaceDim)
                Text("KREATIN 101 šćčžđ", fontFamily = family, style = MaterialTheme.typography.titleLarge)
            }
            if (selected) Icon(Icons.Default.Check, contentDescription = "Selected", tint = Accent)
        }
    }

    OutlinedButton(
        onClick = { pickFont.launch(arrayOf("font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream")) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Import a font file (.ttf / .otf)") }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Accent) }
}

@Composable
private fun SizeTab(style: AnimationStyle, onPreview: (AnimationStyle) -> Unit, onCommit: () -> Unit, onSet: (AnimationStyle) -> Unit) {
    LabeledSlider(
        label = "Text size",
        valueText = "${(style.textSize * 100).roundToInt()}%",
        value = style.textSize.toFloat(),
        range = 0.02f..0.2f,
        onChange = { onPreview(style.copy(textSize = it.toDouble())) },
        onFinished = onCommit,
    )
    LabeledSlider(
        label = "Horizontal position",
        valueText = "${(style.positionX * 100).roundToInt()}%",
        value = style.positionX.toFloat(),
        range = 0f..1f,
        onChange = { onPreview(style.withPosition(it.toDouble(), style.positionY)) },
        onFinished = onCommit,
    )
    LabeledSlider(
        label = "Vertical position",
        valueText = "${(style.positionY * 100).roundToInt()}%",
        value = style.positionY.toFloat(),
        range = 0f..1f,
        onChange = { onPreview(style.withPosition(style.positionX, it.toDouble())) },
        onFinished = onCommit,
    )
    OutlinedButton(
        onClick = { onSet(style.withPosition(0.5, style.positionY)) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Centre horizontally") }
    Text(
        "Tip: you can also drag the caption directly on the preview.",
        style = MaterialTheme.typography.bodySmall,
        color = OnSurfaceDim,
    )
}

private val palette = listOf(
    Rgb(1.0, 1.0, 1.0), Rgb(0.0, 0.0, 0.0), Rgb(1.0, 0.902, 0.161), Rgb(1.0, 0.62, 0.0),
    Rgb(1.0, 0.27, 0.27), Rgb(1.0, 0.4, 0.7), Rgb(0.65, 0.4, 1.0), Rgb(0.25, 0.45, 1.0),
    Rgb(0.0, 0.85, 1.0), Rgb(0.2, 0.86, 0.5), Rgb(0.6, 1.0, 0.2), Rgb(0.6, 0.6, 0.6),
)

@Composable
private fun ColorsTab(style: AnimationStyle, onPreview: (AnimationStyle) -> Unit, onCommit: () -> Unit) {
    var open by remember { mutableStateOf<String?>(null) }

    @Composable
    fun row(key: String, label: String, color: Rgb, apply: (Rgb) -> AnimationStyle) {
        ColorRow(
            label = label,
            color = color,
            expanded = open == key,
            onToggle = { open = if (open == key) null else key },
            onChange = { onPreview(apply(it)) },
            onCommit = onCommit,
            tag = key,
        )
    }

    row("fill", "Text colour", style.fill) { style.withFill(it) }
    row("highlight", "Spoken word", style.highlight) { style.withHighlight(it) }
    row("outline", "Outline", style.outline) { style.withOutline(it) }
    row("shadow", "Shadow", style.shadow) { style.withShadow(it) }
}

@Composable
private fun ColorRow(
    label: String,
    color: Rgb,
    expanded: Boolean,
    onToggle: () -> Unit,
    onChange: (Rgb) -> Unit,
    onCommit: () -> Unit,
    tag: String,
) {
    Surface(shape = RoundedCornerShape(12.dp), color = Surface2) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(14.dp).testTag("color_$tag"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(hex(color), style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(30.dp).clip(CircleShape).background(color.compose()).border(2.dp, Color.White.copy(alpha = 0.6f), CircleShape))
            }
            if (expanded) ColorPicker(color, onChange, onCommit)
        }
    }
}

@Composable
private fun ColorPicker(color: Rgb, onChange: (Rgb) -> Unit, onCommit: () -> Unit) {
    val hsv = remember(color) { FloatArray(3).also { android.graphics.Color.RGBToHSV(
        (color.r * 255).roundToInt(), (color.g * 255).roundToInt(), (color.b * 255).roundToInt(), it) } }

    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (swatch in palette) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(swatch.compose())
                        .border(2.dp, if (swatch.close(color)) Accent else Color.White.copy(alpha = 0.25f), CircleShape)
                        .clickable { onChange(swatch); onCommit() },
                )
            }
        }

        fun emit(h: Float = hsv[0], s: Float = hsv[1], v: Float = hsv[2]) {
            val c = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
            onChange(Rgb(android.graphics.Color.red(c) / 255.0, android.graphics.Color.green(c) / 255.0, android.graphics.Color.blue(c) / 255.0))
        }
        ColorSlider("Hue", hsv[0], 0f..360f, { emit(h = it) }, onCommit)
        ColorSlider("Saturation", hsv[1], 0f..1f, { emit(s = it) }, onCommit)
        ColorSlider("Brightness", hsv[2], 0f..1f, { emit(v = it) }, onCommit)
    }
}

@Composable
private fun ColorSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, onFinished: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim, modifier = Modifier.width(76.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onFinished,
            valueRange = range,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Surface3),
        )
    }
}

@Composable
private fun OutlineTab(style: AnimationStyle, onPreview: (AnimationStyle) -> Unit, onCommit: () -> Unit, onSet: (AnimationStyle) -> Unit) {
    SwitchRow("Outline", style.outlineEnabled) { onSet(style.copy(outlineEnabled = it)) }
    LabeledSlider(
        label = "Outline thickness",
        valueText = "${(style.outlineThickness * 100).roundToInt()}",
        value = style.outlineThickness.toFloat(),
        range = 0f..0.25f,
        enabled = style.outlineEnabled,
        onChange = { onPreview(style.copy(outlineThickness = it.toDouble())) },
        onFinished = onCommit,
    )
    SwitchRow("Shadow", style.shadowEnabled) { onSet(style.copy(shadowEnabled = it)) }
    LabeledSlider(
        label = "Shadow distance",
        valueText = "${(style.shadowOffset * 100).roundToInt()}",
        value = style.shadowOffset.toFloat(),
        range = 0f..0.15f,
        enabled = style.shadowEnabled,
        onChange = { onPreview(style.copy(shadowOffset = it.toDouble())) },
        onFinished = onCommit,
    )
}

@Composable
private fun MotionTab(style: AnimationStyle, onPreview: (AnimationStyle) -> Unit, onCommit: () -> Unit, onSet: (AnimationStyle) -> Unit) {
    SwitchRow("Highlight the spoken word", style.highlightEnabled) { onSet(style.copy(highlightEnabled = it)) }
    LabeledSlider(
        label = "Word pop",
        valueText = "${((style.highlightPopScale - 1) * 100).roundToInt()}%",
        value = style.highlightPopScale.toFloat(),
        range = 1f..1.4f,
        enabled = style.highlightEnabled,
        onChange = { onPreview(style.copy(highlightPopScale = it.toDouble())) },
        onFinished = onCommit,
    )
    LabeledSlider(
        label = "Animation length",
        valueText = "%.2f s".format(style.animationLength),
        value = style.animationLength.toFloat(),
        range = 0.05f..0.6f,
        onChange = { onPreview(style.copy(animationLength = it.toDouble())) },
        onFinished = onCommit,
    )
    Text("Caption entrance", style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim)
    SwitchRow("Pop in", style.popInEnabled) { onSet(style.copy(popInEnabled = it)) }
    SwitchRow("Slide up", style.slideUpEnabled) { onSet(style.copy(slideUpEnabled = it)) }
    SwitchRow("Fade", style.fadeEnabled) { onSet(style.copy(fadeEnabled = it)) }
}

private class BuiltInPreset(val name: String, val style: AnimationStyle)

private val builtIns = listOf(
    BuiltInPreset("Yellow pop", AnimationStyle()),
    BuiltInPreset(
        "Green pulse",
        AnimationStyle(font = "Anton", bold = false, textSize = 0.085).withHighlight(Rgb(0.2, 0.86, 0.5)),
    ),
    BuiltInPreset(
        "Hot pink",
        AnimationStyle(font = "Barlow Condensed ExtraBold", textSize = 0.1).withHighlight(Rgb(1.0, 0.25, 0.6)),
    ),
    BuiltInPreset(
        "Clean white",
        AnimationStyle(font = "Roboto", textSize = 0.065, outlineThickness = 0.05, highlightPopScale = 1.05)
            .withHighlight(Rgb(1.0, 1.0, 1.0)),
    ),
)

@Composable
private fun PresetsTab(style: AnimationStyle, fonts: FontRegistry, onSet: (AnimationStyle) -> Unit) {
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }

    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            Presets.parse(text) to Presets.nameOf(text)
        }.onSuccess { (parsed, name) ->
            onSet(parsed)
            message = "Loaded “${name ?: "preset"}”" +
                if (!fonts.has(parsed.font)) " — font “${parsed.font}” isn't installed; import it from the Font tab." else ""
        }.onFailure { message = "That doesn't look like a caption preset." }
    }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)!!.bufferedWriter().use {
                it.write(Presets.serialize(style, "DPX Captions preset"))
            }
        }.onSuccess { message = "Preset saved." }.onFailure { message = "Couldn't save the preset." }
    }

    Text("Built-in looks", style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim)
    for (preset in builtIns) {
        val family = remember(preset.name) { FontFamily(fonts.resolve(preset.style.font, preset.style.bold).typeface) }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Surface2)
                .clickable {
                    // Keep where the captions sit; a preset changes the look, not the layout.
                    onSet(preset.style.withPosition(style.positionX, style.positionY))
                }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(preset.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("SPOKEN", fontFamily = family, color = preset.style.highlight.compose(), style = MaterialTheme.typography.titleMedium)
        }
    }

    OutlinedButton(
        onClick = { import.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Import an AutoSubs preset (.json)") }
    OutlinedButton(
        onClick = { export.launch("my-style.autosubs-preset.json") },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Save current style as preset") }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Accent) }
}

private fun Rgb.compose() = Color(r.toFloat().coerceIn(0f, 1f), g.toFloat().coerceIn(0f, 1f), b.toFloat().coerceIn(0f, 1f))

private fun Rgb.close(other: Rgb) =
    kotlin.math.abs(r - other.r) < 0.02 && kotlin.math.abs(g - other.g) < 0.02 && kotlin.math.abs(b - other.b) < 0.02

private fun hex(c: Rgb) = "#%02X%02X%02X".format(
    (c.r * 255).roundToInt().coerceIn(0, 255), (c.g * 255).roundToInt().coerceIn(0, 255), (c.b * 255).roundToInt().coerceIn(0, 255),
)
