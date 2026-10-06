package com.dpx.captions.core

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever

data class VideoMeta(
    /** Dimensions as displayed, i.e. already swapped for phone clips stored sideways with a rotation flag. */
    val width: Int,
    val height: Int,
    val durationSeconds: Double,
    val fps: Double,
    val bitrate: Long,
    val hasAudio: Boolean,
)

object VideoProbe {
    fun read(path: String): VideoMeta {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            fun meta(key: Int) = retriever.extractMetadata(key)

            val rawWidth = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val rawHeight = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val sideways = rotation == 90 || rotation == 270

            return VideoMeta(
                width = if (sideways) rawHeight else rawWidth,
                height = if (sideways) rawWidth else rawHeight,
                durationSeconds = (meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0,
                fps = frameRate(path) ?: 30.0,
                bitrate = meta(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L,
                hasAudio = meta(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
            )
        } finally {
            retriever.release()
        }
    }

    private fun frameRate(path: String): Double? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?.takeIf { it.containsKey(MediaFormat.KEY_FRAME_RATE) }
                ?.let { runCatching { it.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrNull() }
        } catch (e: Exception) {
            null
        } finally {
            extractor.release()
        }
    }
}
