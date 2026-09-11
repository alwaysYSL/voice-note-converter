package com.example.audio

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class PlaybackState(
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val progress: Float = 0f,
    val errorMessage: String? = null
)

class AudioPreviewPlayer(
    context: Context,
    private val scope: CoroutineScope
) {
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()
    private val player = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true
        )
        .build()
    private var progressJob: Job? = null
    private var currentUri: Uri? = null
    private var clipStart = 0L
    private var clipEnd = Long.MAX_VALUE

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_ENDED -> {
                        stopProgress()
                        _playbackState.value = _playbackState.value.copy(
                            isPlaying = false,
                            currentPositionMs = _playbackState.value.totalDurationMs,
                            progress = 1f
                        )
                    }
                    Player.STATE_READY -> update()
                    Player.STATE_IDLE, Player.STATE_BUFFERING -> Unit
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                _playbackState.value = _playbackState.value.copy(isPlaying = playing)
                if (playing) startProgress() else stopProgress()
            }

            override fun onPlayerError(error: PlaybackException) {
                stopProgress()
                _playbackState.value = _playbackState.value.copy(
                    isPlaying = false,
                    errorMessage = error.localizedMessage ?: "Audio tidak dapat diputar."
                )
            }
        })
    }

    fun play(uri: Uri) {
        stopPlayback(resetPlaybackParameters = false)
        currentUri = uri
        player.setMediaItem(mediaItem(uri))
        player.prepare()
        player.playWhenReady = true
    }

    fun play(file: File) = play(file.toUri())

    fun pause() {
        player.playWhenReady = false
    }

    fun resume() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.playWhenReady = true
    }

    fun togglePlayPause() {
        if (player.isPlaying) {
            pause()
        } else if (currentUri != null && player.playbackState == Player.STATE_IDLE) {
            play(requireNotNull(currentUri))
        } else {
            resume()
        }
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0))
        update()
    }

    fun setClipping(startMs: Long, endMs: Long) {
        clipStart = startMs.coerceAtLeast(0)
        clipEnd = endMs.coerceAtLeast(clipStart)
        currentUri?.let(::replaceItem)
    }

    fun clearClipping() {
        clipStart = 0
        clipEnd = Long.MAX_VALUE
        currentUri?.let(::replaceItem)
    }

    /**
     * Set the pitch preview factor while preserving the current playback speed.
     * ExoPlayer's built-in Sonic processor is used for this responsive preview.
     */
    fun setPitchPreview(factor: Float) {
        val currentSpeed = player.playbackParameters.speed
        player.playbackParameters = PlaybackParameters(
            currentSpeed,
            factor.coerceIn(0.25f, 4f)
        )
    }

    fun stop() {
        stopPlayback()
        currentUri = null
        clipStart = 0L
        clipEnd = Long.MAX_VALUE
    }

    private fun stopPlayback(resetPlaybackParameters: Boolean = true) {
        stopProgress()
        player.stop()
        player.clearMediaItems()
        if (resetPlaybackParameters) {
            player.playbackParameters = PlaybackParameters.DEFAULT
        }
        _playbackState.value = PlaybackState()
    }

    fun release() {
        stop()
        player.release()
    }

    private fun mediaItem(uri: Uri): MediaItem =
        if (clipStart > 0 || clipEnd < Long.MAX_VALUE) {
            MediaItem.Builder()
                .setUri(uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clipStart)
                        .setEndPositionMs(
                            if (clipEnd == Long.MAX_VALUE) C.TIME_END_OF_SOURCE else clipEnd
                        )
                        .build()
                )
                .build()
        } else {
            MediaItem.fromUri(uri)
        }

    private fun replaceItem(uri: Uri) {
        val wasPlaying = player.isPlaying
        player.setMediaItem(mediaItem(uri))
        player.prepare()
        player.playWhenReady = wasPlaying
    }

    private fun startProgress() {
        stopProgress()
        progressJob = scope.launch {
            while (isActive && player.isPlaying) {
                update()
                delay(16)
            }
        }
    }

    private fun stopProgress() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun update() {
        val duration = player.duration.coerceAtLeast(0)
        val position = player.currentPosition.coerceIn(0, duration)
        val progress = if (duration > 0) position.toFloat() / duration else 0f
        _playbackState.value = PlaybackState(
            isPlaying = player.isPlaying,
            currentPositionMs = position,
            totalDurationMs = duration,
            progress = progress.coerceIn(0f, 1f)
        )
    }
}
