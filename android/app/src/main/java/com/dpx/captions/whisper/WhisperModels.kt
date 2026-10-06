package com.dpx.captions.whisper

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

data class WhisperModel(
    val id: String,
    val label: String,
    val fileName: String,
    val sizeBytes: Long,
    /** Name used to pick the DTW alignment-head preset in the native layer. */
    val dtwName: String,
    val hint: String,
    val recommended: Boolean = false,
) {
    val url: String get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"
}

object WhisperCatalog {
    val models = listOf(
        WhisperModel(
            "large-v3-turbo-q5_0", "Whisper Large v3 Turbo", "ggml-large-v3-turbo-q5_0.bin",
            574_041_195L, "large-v3-turbo",
            "Best balance of speed and Serbian accuracy", recommended = true,
        ),
        WhisperModel(
            "large-v3-q5_0", "Whisper Large v3", "ggml-large-v3-q5_0.bin",
            1_081_140_203L, "large-v3",
            "Most accurate, about twice as slow as Turbo",
        ),
        WhisperModel(
            "medium-q5_0", "Whisper Medium", "ggml-medium-q5_0.bin",
            539_212_467L, "medium",
            "Faster, noticeably weaker Serbian",
        ),
        WhisperModel(
            "small-q5_1", "Whisper Small", "ggml-small-q5_1.bin",
            190_085_487L, "small",
            "Quick drafts only; Serbian accuracy is poor",
        ),
        WhisperModel(
            "tiny-q5_1", "Whisper Tiny", "ggml-tiny-q5_1.bin",
            32_152_673L, "tiny",
            "For testing the app; not usable for real captions",
        ),
    )

    fun byId(id: String): WhisperModel = models.firstOrNull { it.id == id } ?: models.first()
}

class ModelStore(context: Context) {
    private val dir = File(context.filesDir, "models").apply { mkdirs() }

    fun file(model: WhisperModel) = File(dir, model.fileName)

    private fun partFile(model: WhisperModel) = File(dir, model.fileName + ".part")

    /** A file of the wrong size is a truncated or corrupted download and must not be loaded. */
    fun isInstalled(model: WhisperModel): Boolean =
        file(model).let { it.exists() && it.length() == model.sizeBytes }

    fun partialBytes(model: WhisperModel): Long = partFile(model).takeIf { it.exists() }?.length() ?: 0L

    fun delete(model: WhisperModel) {
        file(model).delete()
        partFile(model).delete()
    }

    /**
     * Downloads a model, resuming from a `.part` file if an earlier attempt was interrupted. Mobile
     * connections drop mid-download of a 500 MB+ file all the time, so a few retries are built in.
     */
    suspend fun download(model: WhisperModel, onProgress: (downloaded: Long, total: Long) -> Unit) {
        withContext(Dispatchers.IO) {
            if (isInstalled(model)) return@withContext

            var lastError: Exception? = null
            repeat(MAX_ATTEMPTS) { attempt ->
                try {
                    attemptDownload(model, onProgress)
                    return@withContext
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    lastError = e
                    delay(1500L * (attempt + 1))
                }
            }
            throw IOException("Download failed: ${lastError?.message}", lastError)
        }
    }

    private suspend fun attemptDownload(model: WhisperModel, onProgress: (Long, Long) -> Unit) {
        val part = partFile(model)
        var offset = if (part.exists()) part.length() else 0L
        if (offset > model.sizeBytes) {
            part.delete()
            offset = 0L
        }

        val connection = (URL(model.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }

        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("Server returned HTTP $code")
            }
            // A server that ignores Range answers 200 with the whole body; start over in that case.
            if (code == HttpURLConnection.HTTP_OK && offset > 0) {
                part.delete()
                offset = 0L
            }

            RandomAccessFile(part, "rw").use { out ->
                out.seek(offset)
                var downloaded = offset
                val buffer = ByteArray(256 * 1024)
                var lastReport = 0L
                connection.inputStream.use { input ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        downloaded += read
                        val now = System.nanoTime()
                        if (now - lastReport > 150_000_000L) {
                            lastReport = now
                            onProgress(downloaded, model.sizeBytes)
                        }
                    }
                }
                onProgress(downloaded, model.sizeBytes)
            }
        } finally {
            connection.disconnect()
        }

        if (part.length() != model.sizeBytes) {
            throw IOException("Incomplete download (${part.length()} of ${model.sizeBytes} bytes)")
        }
        val target = file(model)
        target.delete()
        if (!part.renameTo(target)) throw IOException("Could not finalise the downloaded model")
    }

    private companion object {
        const val MAX_ATTEMPTS = 4
    }
}
