package com.dpx.captions.ui.editor

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import java.io.File

/**
 * Wraps ExoPlayer and owns the playhead position as Compose state. The position is *ours*, not the
 * player's: while scrubbing it is set straight from the finger, and only while playing is it read back
 * from the player. Mirroring the player's reported position instead would make the timeline jitter,
 * because a fast seek lands on the nearest keyframe rather than the exact time asked for.
 */
class PlayerController(context: Context, videoPath: String) {
    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        setMediaItem(MediaItem.fromUri(Uri.fromFile(File(videoPath))))
        repeatMode = Player.REPEAT_MODE_OFF
        playWhenReady = false
        prepare()
    }

    var positionMs by mutableLongStateOf(0L)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY && player.duration > 0) durationMs = player.duration
            if (state == Player.STATE_ENDED) {
                positionMs = player.duration.coerceAtLeast(0L)
                isPlaying = false
            }
        }
    }

    init {
        player.addListener(listener)
    }

    val positionSeconds: Double get() = positionMs / 1000.0

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
            return
        }
        if (player.playbackState == Player.STATE_ENDED) seekTo(0)
        player.play()
    }

    fun pause() = player.pause()

    /** [scrubbing] trades frame accuracy for speed while a finger is dragging the timeline. */
    fun seekTo(ms: Long, scrubbing: Boolean = false) {
        val limit = if (durationMs > 0) durationMs else Long.MAX_VALUE
        val target = ms.coerceIn(0L, limit)
        player.setSeekParameters(if (scrubbing) SeekParameters.CLOSEST_SYNC else SeekParameters.EXACT)
        positionMs = target
        player.seekTo(target)
    }

    /** Called once per frame while playing. */
    fun tick() {
        if (player.isPlaying) positionMs = player.currentPosition
    }

    fun release() {
        player.removeListener(listener)
        player.release()
    }
}
