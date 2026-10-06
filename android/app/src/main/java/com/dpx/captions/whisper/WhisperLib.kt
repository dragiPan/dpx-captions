package com.dpx.captions.whisper

import java.io.File

/** Thin binding over the native whisper.cpp bridge in src/main/cpp/whisper_jni.cpp. */
object WhisperLib {
    fun interface ProgressListener {
        fun onProgress(percent: Int)
    }

    init {
        System.loadLibrary("dpxwhisper")
    }

    @JvmStatic external fun initContext(modelPath: String, modelName: String): Long

    @JvmStatic external fun freeContext(handle: Long)

    @JvmStatic external fun abort()

    @JvmStatic external fun systemInfo(): String

    /** Returns UTF-8 JSON bytes (`{"words":[...]}`), or null on failure or cancellation. */
    @JvmStatic external fun transcribe(
        handle: Long,
        samples: FloatArray,
        language: String,
        prompt: String,
        threads: Int,
        beamSize: Int,
        listener: ProgressListener?,
    ): ByteArray?

    /**
     * The native build targets Armv8.2 dot-product + half-precision arithmetic. Running it on an older
     * core would crash with an illegal instruction, so check the CPU first and fail with a message.
     */
    fun cpuSupported(): Boolean {
        if (android.os.Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a") return true
        val features = runCatching {
            File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("Features") }.orEmpty()
        }.getOrDefault("")
        val flags = features.split(Regex("\\s+")).toSet()
        return "asimddp" in flags && ("asimdhp" in flags || "fphp" in flags)
    }
}
