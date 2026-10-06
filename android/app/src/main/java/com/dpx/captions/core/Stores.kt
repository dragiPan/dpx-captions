package com.dpx.captions.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.dpx.captions.model.AnimationStyle
import com.dpx.captions.model.Aspect
import com.dpx.captions.model.CaptionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import com.dpx.captions.model.Word
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.UUID

/** Writes via a temp file and rename so a crash mid-save can never leave a half-written project. */
internal fun File.writeAtomically(text: String) {
    val temp = File(parentFile, "$name.tmp")
    temp.writeText(text)
    if (!temp.renameTo(this)) {
        delete()
        check(temp.renameTo(this)) { "Could not write $name" }
    }
}

class SettingsStore(context: Context) {
    private val file = File(context.filesDir, "settings.json")
    private val flow = MutableStateFlow(load())

    val state: StateFlow<AppSettings> = flow

    private fun load(): AppSettings = runCatching { AppSettings.fromJson(file.readText()) }.getOrDefault(AppSettings())

    fun update(transform: (AppSettings) -> AppSettings) {
        val updated = flow.updateAndGet(transform)
        runCatching { file.writeAtomically(updated.toJson()) }
    }

    private fun <T> MutableStateFlow<T>.updateAndGet(transform: (T) -> T): T {
        update(transform)
        return value
    }
}

data class ProjectSummary(
    val id: String,
    val name: String,
    val durationSeconds: Double,
    val cardCount: Int,
    val modifiedAt: Long,
    val width: Int,
    val height: Int,
)

class ProjectStore(private val context: Context) {
    private val root = File(context.filesDir, "projects").apply { mkdirs() }

    fun dir(id: String) = File(root, id)

    private fun projectFile(id: String) = File(dir(id), "project.json")

    fun audioCache(id: String) = File(dir(id), "audio16k.f32")

    fun thumbnail(id: String) = File(dir(id), "thumb.jpg")

    private fun writeThumbnail(videoPath: String, id: String, durationSeconds: Double) {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoPath)
            val atUs = (minOf(1.0, durationSeconds / 2) * 1_000_000).toLong()
            val frame = retriever.getFrameAtTime(atUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return
            val scale = 360f / maxOf(frame.width, frame.height)
            val scaled = android.graphics.Bitmap.createScaledBitmap(
                frame, (frame.width * scale).toInt().coerceAtLeast(1), (frame.height * scale).toInt().coerceAtLeast(1), true,
            )
            thumbnail(id).outputStream().use { scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, it) }
        } catch (_: Exception) {
            // A missing thumbnail is cosmetic and must never fail an import.
        } finally {
            retriever.release()
        }
    }

    private fun wordsFile(id: String) = File(dir(id), "words.json")

    fun exportsDir() = File(context.cacheDir, "exports").apply { mkdirs() }

    fun list(): List<ProjectSummary> =
        (root.listFiles { f -> f.isDirectory } ?: emptyArray())
            .mapNotNull { dir -> load(dir.name) }
            .sortedByDescending { it.modifiedAt }
            .map {
                ProjectSummary(it.id, it.name, it.durationSeconds, it.cards.size, it.modifiedAt, it.width, it.height)
            }

    fun load(id: String): ProjectFile? = runCatching { ProjectFile.fromJson(projectFile(id).readText()) }.getOrNull()

    fun save(project: ProjectFile): ProjectFile {
        val stamped = project.copy(modifiedAt = System.currentTimeMillis())
        dir(project.id).mkdirs()
        projectFile(project.id).writeAtomically(stamped.toJson())
        return stamped
    }

    fun delete(id: String) {
        dir(id).deleteRecursively()
    }

    fun saveWords(id: String, words: List<Word>) {
        wordsFile(id).writeAtomically(AppJson.encodeToString(ListSerializer(Word.serializer()), words))
    }

    fun loadWords(id: String): List<Word>? =
        runCatching { AppJson.decodeFromString(ListSerializer(Word.serializer()), wordsFile(id).readText()) }.getOrNull()

    /**
     * Copies the picked video into app storage. Gallery URIs can lose permission or vanish, and a
     * project that can't reopen its own clip is worse than the disk space the copy costs.
     */
    suspend fun import(uri: Uri, settings: AppSettings, onProgress: (Float) -> Unit): ProjectFile =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString().take(12)
            val resolver = context.contentResolver

            var displayName = "Video"
            var size = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { displayName = c.getString(it) ?: displayName }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
                }
            }

            if (displayName.substringBeforeLast('.').all { it.isDigit() }) {
                displayName = "Video " + java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
            }

            val extension = displayName.substringAfterLast('.', "mp4").lowercase().takeIf { it.length in 2..4 } ?: "mp4"
            val target = File(dir(id).apply { mkdirs() }, "source.$extension")

            try {
                resolver.openInputStream(uri)!!.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(512 * 1024)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (size > 0) onProgress((copied.toFloat() / size).coerceIn(0f, 1f))
                        }
                    }
                }

                val meta = VideoProbe.read(target.absolutePath)
                require(meta.width > 0 && meta.height > 0 && meta.durationSeconds > 0) { "This file isn't a readable video" }
                writeThumbnail(target.absolutePath, id, meta.durationSeconds)

                val now = System.currentTimeMillis()
                save(
                    ProjectFile(
                        id = id,
                        name = displayName.substringBeforeLast('.'),
                        videoPath = target.absolutePath,
                        modelSize = settings.modelId,
                        formatting = settings.formatting,
                        style = settings.style,
                        aspect = settings.aspect,
                        cards = emptyList<CaptionCard>(),
                        width = meta.width,
                        height = meta.height,
                        durationSeconds = meta.durationSeconds,
                        fps = meta.fps,
                        createdAt = now,
                        modifiedAt = now,
                    ),
                )
            } catch (e: Exception) {
                dir(id).deleteRecursively()
                throw e
            }
        }
}
