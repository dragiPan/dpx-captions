package com.dpx.captions.render

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale

/**
 * Resolves the font names stored in styles/presets to Typefaces. Preview and export both go through
 * here, so a name always looks the same in both.
 *
 * Fonts come from three places: a few OFL faces bundled in assets, font files the user imports
 * (e.g. Franklin Gothic Demi copied from a PC), and Android's own system families.
 */
class FontRegistry(private val context: Context) {

    /** [fakeBold] is true when the Bold flag must be synthesised because the face isn't bold itself. */
    data class Resolved(val typeface: Typeface, val fakeBold: Boolean)

    private class Entry(
        val name: String,
        val build: (bold: Boolean) -> Typeface?,
        /** True when the face is already heavy, so the Bold flag must not embolden it again. */
        val heavy: Boolean,
        val imported: Boolean = false,
    )

    private val entries = LinkedHashMap<String, Entry>()
    private val aliases = HashMap<String, String>()
    private val cache = HashMap<String, Typeface>()

    private val fontsDir = File(context.filesDir, "fonts").apply { mkdirs() }

    init {
        registerBundled()
        scanImported()
        registerSystem()
    }

    val defaultName = "Open Sans Condensed"

    fun names(): List<String> = entries.keys.toList()

    fun isImported(name: String): Boolean = entries[canonical(name)]?.imported == true

    /** Whether [name] resolves to a real font, as opposed to silently falling back to the default. */
    fun has(name: String): Boolean = canonical(name) in entries

    fun resolve(name: String, bold: Boolean): Resolved {
        val key = canonical(name)
        val entry = entries[key] ?: entries.getValue(defaultName.lowercase(Locale.ROOT).let { aliases[it] ?: defaultName })
        val cacheKey = "${entry.name}|$bold"
        val typeface = cache.getOrPut(cacheKey) { entry.build(bold) ?: Typeface.DEFAULT }
        return Resolved(typeface, fakeBold = bold && !entry.heavy)
    }

    private fun canonical(name: String): String {
        val lower = name.trim().lowercase(Locale.ROOT)
        return aliases[lower] ?: entries.keys.firstOrNull { it.lowercase(Locale.ROOT) == lower } ?: name
    }

    // --- registration ----------------------------------------------------------------------------

    private fun add(entry: Entry, vararg extraAliases: String) {
        entries[entry.name] = entry
        aliases[entry.name.lowercase(Locale.ROOT)] = entry.name
        extraAliases.forEach { aliases[it.lowercase(Locale.ROOT)] = entry.name }
    }

    private fun registerBundled() {
        fun variable(name: String, width: Int, vararg extra: String) = add(
            Entry(name, { bold ->
                Typeface.Builder(context.assets, "fonts/OpenSans.ttf")
                    .setFontVariationSettings("'wght' ${if (bold) 700 else 400}, 'wdth' $width")
                    .build()
            }, heavy = true),
            *extra,
        )

        fun static(name: String, file: String, vararg extra: String) = add(
            Entry(name, { Typeface.createFromAsset(context.assets, "fonts/$file") }, heavy = true),
            *extra,
        )

        // The weight axis is driven by the Bold flag, so no extra synthetic bolding is wanted.
        variable("Open Sans Condensed", 75, "OpenSans-CondensedBold", "Open Sans Condensed Bold")
        variable("Open Sans", 100)
        static("Barlow Condensed", "BarlowCondensed-Bold.ttf", "Barlow Condensed Bold")
        static("Barlow Condensed ExtraBold", "BarlowCondensed-ExtraBold.ttf")
        static("Barlow Condensed Black", "BarlowCondensed-Black.ttf")
        static("Anton", "Anton-Regular.ttf")
        static("Bebas Neue", "BebasNeue-Regular.ttf")
        static("Fjalla One", "FjallaOne-Regular.ttf")
    }

    private fun registerSystem() {
        fun system(name: String, family: String) = add(
            Entry(name, { bold -> Typeface.create(family, if (bold) Typeface.BOLD else Typeface.NORMAL) }, heavy = true),
        )
        system("Roboto", "sans-serif")
        system("Roboto Condensed", "sans-serif-condensed")
        system("Serif", "serif")
        system("Monospace", "monospace")
    }

    private fun scanImported() {
        fontsDir.listFiles { f -> f.extension.lowercase(Locale.ROOT) in setOf("ttf", "otf") }
            ?.sortedBy { it.name }
            ?.forEach { registerImportedFile(it) }
    }

    private fun registerImportedFile(file: File): String? {
        val info = FontFileInfo.read(file) ?: return null
        val display = info.fullName ?: info.family ?: file.nameWithoutExtension
        val typeface = runCatching { Typeface.createFromFile(file) }.getOrNull() ?: return null
        add(
            Entry(display, { typeface }, heavy = info.weight >= 600, imported = true),
            *listOfNotNull(info.family, info.typographicFamily, info.fullName).toTypedArray(),
        )
        return display
    }

    /** Copies a user-picked font into app storage and registers it. Returns its display name. */
    fun importFont(uri: Uri): String? {
        val temp = File(fontsDir, "import.tmp")
        context.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { input.copyTo(it) }
        } ?: return null

        val info = FontFileInfo.read(temp)
        if (info == null) {
            temp.delete()
            return null
        }
        val extension = if (FontFileInfo.isOpenType(temp)) "otf" else "ttf"
        val safeName = (info.fullName ?: info.family ?: "font").replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(fontsDir, "$safeName.$extension")
        target.delete()
        temp.renameTo(target)
        return registerImportedFile(target)
    }

    fun removeImported(name: String) {
        val key = canonical(name)
        val entry = entries[key] ?: return
        if (!entry.imported) return
        entries.remove(key)
        aliases.entries.removeAll { it.value == key }
        cache.keys.removeAll { it.startsWith("$key|") }
        fontsDir.listFiles()?.forEach { file ->
            if (FontFileInfo.read(file)?.let { (it.fullName ?: it.family) == key } == true) file.delete()
        }
    }
}

/** Just enough of the sfnt `name` and `OS/2` tables to identify an imported font. */
class FontFileInfo(
    val family: String?,
    val typographicFamily: String?,
    val fullName: String?,
    val weight: Int,
) {
    companion object {
        fun isOpenType(file: File): Boolean =
            runCatching { file.inputStream().use { it.readNBytes(4).decodeToString() == "OTTO" } }.getOrDefault(false)

        fun read(file: File): FontFileInfo? = runCatching { parse(file.readBytes()) }.getOrNull()

        private fun parse(data: ByteArray): FontFileInfo? {
            if (data.size < 12) return null
            val buf = ByteBuffer.wrap(data) // big-endian by default, as sfnt requires
            val tag = String(data, 0, 4, Charsets.ISO_8859_1)
            // Font collections hold several faces; supporting them isn't worth the complexity here.
            if (tag == "ttcf" || (tag != "OTTO" && buf.getInt(0) != 0x00010000 && tag != "true")) return null

            val tableCount = buf.getShort(4).toInt() and 0xFFFF
            var nameOffset = -1
            var os2Offset = -1
            for (i in 0 until tableCount) {
                val base = 12 + i * 16
                if (base + 16 > data.size) break
                when (String(data, base, 4, Charsets.ISO_8859_1)) {
                    "name" -> nameOffset = buf.getInt(base + 8)
                    "OS/2" -> os2Offset = buf.getInt(base + 8)
                }
            }
            if (nameOffset < 0) return null

            val count = buf.getShort(nameOffset + 2).toInt() and 0xFFFF
            val stringBase = nameOffset + (buf.getShort(nameOffset + 4).toInt() and 0xFFFF)
            val found = HashMap<Int, String>()
            for (i in 0 until count) {
                val rec = nameOffset + 6 + i * 12
                val platform = buf.getShort(rec).toInt() and 0xFFFF
                val nameId = buf.getShort(rec + 6).toInt() and 0xFFFF
                val length = buf.getShort(rec + 8).toInt() and 0xFFFF
                val offset = buf.getShort(rec + 10).toInt() and 0xFFFF
                if (nameId !in setOf(1, 4, 16) || nameId in found && platform == 1) continue
                val start = stringBase + offset
                if (start + length > data.size) continue
                val text = when (platform) {
                    0, 3 -> String(data, start, length, Charsets.UTF_16BE)
                    1 -> String(data, start, length, Charsets.ISO_8859_1)
                    else -> continue
                }.trim()
                if (text.isNotEmpty()) found[nameId] = text
            }

            val weight = if (os2Offset >= 0 && os2Offset + 6 <= data.size) buf.getShort(os2Offset + 4).toInt() and 0xFFFF else 400
            if (found.isEmpty()) return null
            return FontFileInfo(found[1], found[16], found[4], weight)
        }
    }
}
