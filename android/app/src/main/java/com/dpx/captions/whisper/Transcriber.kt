package com.dpx.captions.whisper

import com.dpx.captions.core.AppJson
import com.dpx.captions.core.Serbian
import com.dpx.captions.model.Word
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable

@Serializable
private data class NativeWords(val words: List<Word> = emptyList())

class TranscriptionException(message: String) : Exception(message)

/**
 * Owns the loaded Whisper context. Loading a model costs seconds and hundreds of MB of RAM, so the
 * context is kept between runs and only replaced when a different model is requested.
 */
class Transcriber(private val store: ModelStore) {
    private var handle = 0L
    private var loadedModelId: String? = null

    @Synchronized
    private fun ensureLoaded(model: WhisperModel) {
        if (handle != 0L && loadedModelId == model.id) return
        release()

        if (!WhisperLib.cpuSupported()) {
            throw TranscriptionException(
                "This phone's processor lacks the instructions on-device transcription needs " +
                    "(Armv8.2 dot-product).",
            )
        }
        if (!store.isInstalled(model)) {
            throw TranscriptionException("${model.label} is not downloaded yet.")
        }

        handle = WhisperLib.initContext(store.file(model).absolutePath, model.dtwName)
        if (handle == 0L) {
            throw TranscriptionException(
                "Could not load ${model.label}. The phone may not have enough free memory for it - " +
                    "try a smaller model.",
            )
        }
        loadedModelId = model.id
    }

    @Synchronized
    fun release() {
        if (handle != 0L) WhisperLib.freeContext(handle)
        handle = 0L
        loadedModelId = null
    }

    /**
     * Runs on a background dispatcher. The native call can't be interrupted by coroutine cancellation,
     * so cancelling the job flips a flag the native abort callback polls.
     */
    suspend fun transcribe(
        samples: FloatArray,
        model: WhisperModel,
        vocabularyContext: String,
        threads: Int,
        beamSize: Int,
        onProgress: (Int) -> Unit,
    ): List<Word> = coroutineScope {
        val work = async(Dispatchers.Default) {
            ensureLoaded(model)
            val bytes = WhisperLib.transcribe(
                handle, samples, "sr", vocabularyContext, threads, beamSize,
                WhisperLib.ProgressListener { onProgress(it) },
            )
            if (bytes == null) {
                ensureActive()
                throw TranscriptionException("Transcription failed")
            }
            AppJson.decodeFromString<NativeWords>(String(bytes, Charsets.UTF_8)).words.mapNotNull(::cleanWord)
        }

        try {
            work.await()
        } catch (e: CancellationException) {
            // The native call can't be interrupted by cancellation, so flip the flag its abort
            // callback polls; coroutineScope then waits for it to unwind before propagating.
            WhisperLib.abort()
            throw e
        }
    }

    private fun cleanWord(word: Word): Word? {
        val text = Serbian.cyrillicToLatin(word.text).trim()
        if (text.isEmpty()) return null
        // Whisper sometimes narrates sound instead of speech: "[Music]", "(applause)", "♪".
        if (text.startsWith("[") && text.endsWith("]")) return null
        if (text.startsWith("(") && text.endsWith(")")) return null
        if (text.all { it == '♪' || it == '*' || it == '-' }) return null
        return word.copy(text = text)
    }
}
