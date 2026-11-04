package com.dpx.captions.core

import com.dpx.captions.model.AnimationStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Looks the user saved under a name, to switch back to in one tap. Each is an ordinary
 * `.autosubs-preset.json` (named after the file, like the desktop app's presets), so the same files
 * load in AutoSubs and on the desktop.
 */
class UserPresets(private val dir: File) {
    data class Preset(val name: String, val style: AnimationStyle)

    private val flow = MutableStateFlow(scan())
    val presets: StateFlow<List<Preset>> = flow

    private fun file(name: String) = File(dir, name.replace(Regex("[<>:\"/\\\\|?*]"), "_").trim().ifEmpty { "Preset" } + SUFFIX)

    private fun scan(): List<Preset> =
        (dir.listFiles { f -> f.name.endsWith(SUFFIX) } ?: emptyArray())
            .mapNotNull { f -> runCatching { Preset(f.name.removeSuffix(SUFFIX), Presets.parse(f.readText())) }.getOrNull() }
            .sortedBy { it.name.lowercase() }

    fun exists(name: String) = file(name).exists()

    fun save(name: String, style: AnimationStyle) {
        dir.mkdirs()
        file(name).writeAtomically(Presets.serialize(style, name.trim()))
        flow.value = scan()
    }

    fun delete(name: String) {
        file(name).delete()
        flow.value = scan()
    }

    private companion object {
        const val SUFFIX = ".autosubs-preset.json"
    }
}
