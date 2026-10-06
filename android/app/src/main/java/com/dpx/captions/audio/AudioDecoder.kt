package com.dpx.captions.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder

/**
 * Decodes the audio track of a video straight to what Whisper wants: 16 kHz, mono, float. The
 * stream is downmixed and resampled buffer by buffer so a long clip never exists in memory at its
 * native sample rate.
 */
object AudioDecoder {
    const val TARGET_RATE = 16_000

    class NoAudioTrackException : Exception("This video has no audio track")

    fun decode(path: String, onProgress: (Float) -> Unit = {}, isCancelled: () -> Boolean = { false }): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(path)

        val trackIndex = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run {
            extractor.release()
            throw NoAudioTrackException()
        }

        extractor.selectTrack(trackIndex)
        val inputFormat = extractor.getTrackFormat(trackIndex)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
        val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
            inputFormat.getLong(MediaFormat.KEY_DURATION)
        } else 0L

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(inputFormat, null, null, 0)
        codec.start()

        val out = FloatBuffer()
        var resampler: AreaResampler? = null
        var channels = inputFormat.safeInt(MediaFormat.KEY_CHANNEL_COUNT, 1)
        var encoding = AudioFormat.ENCODING_PCM_16BIT

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        try {
            while (!outputDone) {
                if (isCancelled()) throw InterruptedException("cancelled")

                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            if (durationUs > 0) onProgress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        val rate = format.safeInt(MediaFormat.KEY_SAMPLE_RATE, inputFormat.safeInt(MediaFormat.KEY_SAMPLE_RATE, 44_100))
                        channels = format.safeInt(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        encoding = format.safeInt(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        resampler = AreaResampler(rate.toDouble() / TARGET_RATE)
                    }
                    outIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outIndex)!!
                        if (info.size > 0) {
                            if (resampler == null) {
                                val rate = inputFormat.safeInt(MediaFormat.KEY_SAMPLE_RATE, 44_100)
                                resampler = AreaResampler(rate.toDouble() / TARGET_RATE)
                            }
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            feed(buffer, channels, encoding, resampler, out)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }

        return out.toArray()
    }

    private fun feed(
        buffer: java.nio.ByteBuffer,
        channels: Int,
        encoding: Int,
        resampler: AreaResampler,
        out: FloatBuffer,
    ) {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val ch = maxOf(channels, 1)

        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val floats = buffer.asFloatBuffer()
            val frames = floats.remaining() / ch
            for (f in 0 until frames) {
                var sum = 0f
                for (c in 0 until ch) sum += floats.get()
                resampler.process(sum / ch, out)
            }
        } else {
            val shorts = buffer.asShortBuffer()
            val frames = shorts.remaining() / ch
            for (f in 0 until frames) {
                var sum = 0
                for (c in 0 until ch) sum += shorts.get()
                resampler.process(sum / (ch * 32768f), out)
            }
        }
    }

    /** Streaming box-filter resampler: each output sample is the average of the input it spans. */
    private class AreaResampler(private val ratio: Double) {
        private var accumulator = 0.0
        private var weight = 0.0
        private var nextBoundary = ratio
        private var position = 0.0

        fun process(sample: Float, out: FloatBuffer) {
            var start = position
            val end = position + 1.0
            while (start < end) {
                val segmentEnd = minOf(end, nextBoundary)
                val w = segmentEnd - start
                accumulator += sample * w
                weight += w
                start = segmentEnd
                if (segmentEnd >= nextBoundary - 1e-9) {
                    out.add((accumulator / weight).toFloat())
                    accumulator = 0.0
                    weight = 0.0
                    nextBoundary += ratio
                }
            }
            position = end
        }
    }

    /** Growable primitive float list; boxing millions of samples would be ruinous. */
    private class FloatBuffer {
        private var data = FloatArray(TARGET_RATE * 60)
        private var size = 0

        fun add(value: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }

    private fun MediaFormat.safeInt(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default

    private const val TIMEOUT_US = 10_000L

    // --- on-disk cache so re-generating captions doesn't decode the video again -----------------

    fun writeCache(file: File, samples: FloatArray) {
        val bytes = java.nio.ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asFloatBuffer().put(samples)
        file.outputStream().use { it.write(bytes.array()) }
    }

    fun readCache(file: File): FloatArray? {
        if (!file.exists() || file.length() == 0L || file.length() % 4 != 0L) return null
        val bytes = java.nio.ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray((file.length() / 4).toInt()).also { bytes.asFloatBuffer().get(it) }
    }
}

object Waveform {
    const val BUCKETS_PER_SECOND = 100

    /** One normalised 0..1 peak per 1/100 s of audio. */
    fun peaks(samples: FloatArray): FloatArray {
        val perBucket = AudioDecoder.TARGET_RATE / BUCKETS_PER_SECOND
        val buckets = samples.size / perBucket
        if (buckets == 0) return FloatArray(0)

        val peaks = FloatArray(buckets)
        var max = 0f
        for (b in 0 until buckets) {
            var peak = 0f
            val base = b * perBucket
            for (i in 0 until perBucket) {
                val v = kotlin.math.abs(samples[base + i])
                if (v > peak) peak = v
            }
            peaks[b] = peak
            if (peak > max) max = peak
        }
        if (max > 0f) for (i in peaks.indices) peaks[i] /= max
        return peaks
    }
}
