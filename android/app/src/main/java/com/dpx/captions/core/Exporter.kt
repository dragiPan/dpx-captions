package com.dpx.captions.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.common.Effect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.dpx.captions.model.AnimationStyle
import com.dpx.captions.model.Aspect
import com.dpx.captions.model.CaptionCard
import com.dpx.captions.render.CaptionPainter
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

data class ExportOptions(
    val aspect: Aspect = Aspect.AUTO,
    /** Cap on the longer side in pixels; null keeps the source resolution. */
    val maxLongSide: Int? = null,
    /** Multiplier on the source-matched bitrate. */
    val bitrateFactor: Float = 1f,
)

/**
 * Burns captions into the video with Media3's hardware transcoder. Captions come from a bitmap overlay
 * drawn by the same [CaptionPainter] the preview uses, so the exported frames match what was edited.
 */
class Exporter(private val context: Context, private val container: AppContainer) {

    fun outputSize(width: Int, height: Int, options: ExportOptions): Pair<Int, Int> {
        var (w, h) = options.aspect.canvasSize(width, height)
        val cap = options.maxLongSide
        if (cap != null && maxOf(w, h) > cap) {
            val scale = cap.toDouble() / maxOf(w, h)
            w = (w * scale).roundToInt()
            h = (h * scale).roundToInt()
            w += w % 2
            h += h % 2
        }
        return w to h
    }

    fun targetBitrate(project: ProjectFile, outW: Int, outH: Int, factor: Float): Int {
        val meta = VideoProbe.read(project.videoPath)
        val pixels = outW.toLong() * outH
        val sourcePixels = maxOf(project.width.toLong() * project.height, 1L)
        val base = if (meta.bitrate > 0) {
            // The probed figure includes audio; remove a typical AAC share before scaling by resolution.
            ((meta.bitrate - 128_000).coerceAtLeast(500_000) * (pixels.toDouble() / sourcePixels)).toLong()
        } else {
            (pixels * project.fps * 0.08).toLong()
        }
        return (base * factor).toLong().coerceIn(1_500_000L, 80_000_000L).toInt()
    }

    suspend fun export(
        project: ProjectFile,
        options: ExportOptions,
        onProgress: (stage: String, fraction: Float?) -> Unit,
    ): File {
        val (outW, outH) = outputSize(project.width, project.height, options)
        val bitrate = targetBitrate(project, outW, outH, options.bitrateFactor)

        val safeName = project.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "video" }
        val output = File(container.projects.exportsDir(), "${safeName}_captioned.mp4").apply { delete() }

        onProgress("Preparing", null)

        // Always state the output size explicitly. Left to itself, Transformer may encode a portrait clip as
        // landscape and add a rotation flag, which some platforms handle badly on upload.
        val effects = mutableListOf<Effect>(
            Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT),
        )
        effects += OverlayEffect(
            ImmutableList.of<TextureOverlay>(
                CaptionOverlay(container.newPainter(), project.cards, project.style, outW, outH),
            ),
        )

        val item = EditedMediaItem.Builder(MediaItem.fromUri(android.net.Uri.fromFile(File(project.videoPath))))
            .setEffects(Effects(emptyList(), effects))
            .build()

        val composition = Composition.Builder(listOf(EditedMediaItemSequence.Builder(item).build()))
            // Phone footage is often HDR; the overlay pipeline needs SDR frames to blend text correctly.
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()

        return coroutineScope {
            // Transformer insists on being driven from one thread that has a Looper.
            withContext(Dispatchers.Main) {
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setEncoderFactory(
                        DefaultEncoderFactory.Builder(context)
                            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                            .build(),
                    )
                    .build()

                val poller = launch {
                    val holder = ProgressHolder()
                    while (isActive) {
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress("Exporting", holder.progress / 100f)
                        }
                        delay(250)
                    }
                }

                try {
                    suspendCancellableCoroutine { cont ->
                        transformer.addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (cont.isActive) cont.resume(Unit)
                            }

                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                if (cont.isActive) cont.resumeWithException(exportException)
                            }
                        })
                        cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { transformer.cancel() } }
                        transformer.start(composition, output.absolutePath)
                    }
                } finally {
                    poller.cancel()
                }
            }
            output
        }
    }
}

/**
 * Supplies one full-frame bitmap per frame. Media3 only re-uploads a texture when the Bitmap *instance*
 * changes, so the same instance is returned until the caption's appearance actually changes (nearly
 * every frame between word boundaries), and two buffers alternate so a changed frame is never drawn
 * into the bitmap still bound as the previous texture.
 */
private class CaptionOverlay(
    private val painter: CaptionPainter,
    private val cards: List<CaptionCard>,
    private val style: AnimationStyle,
    private val width: Int,
    private val height: Int,
) : BitmapOverlay() {
    private val buffers = Array(2) { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
    private val canvases = Array(2) { Canvas(buffers[it]) }
    private val empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    private var active = 0
    private var lastKey: Int? = null
    private var hasLast = false
    private var last: Bitmap = empty

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val t = presentationTimeUs / 1_000_000.0
        val key = painter.visualState(cards, style, height, t)
        if (hasLast && key == lastKey) return last

        hasLast = true
        lastKey = key
        if (key == null) {
            last = empty
            return empty
        }

        active = 1 - active
        buffers[active].eraseColor(Color.TRANSPARENT)
        painter.draw(canvases[active], width, height, cards, style, t)
        last = buffers[active]
        return last
    }
}
