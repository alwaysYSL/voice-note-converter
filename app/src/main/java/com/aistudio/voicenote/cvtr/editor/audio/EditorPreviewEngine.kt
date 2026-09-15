package com.aistudio.voicenote.cvtr.editor.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.aistudio.voicenote.cvtr.editor.model.EditorSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/** The minimal platform seam needed to test preview scheduling without Android AudioTrack. */
internal interface PreviewAudioSink : AutoCloseable {
    fun play()
    fun pause()
    fun stop()
    fun flush()
    fun queuedFrames(): Long
    fun playedFrames(): Long
    fun write(samples: ShortArray, offset: Int, size: Int): Int
}

internal fun interface PreviewAudioSinkFactory {
    fun create(): PreviewAudioSink
}

internal data class EditorPlaybackState(
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val error: String? = null,
)

/**
 * Feeds a three-second rolling queue. Position is always based on AudioTrack's played head (or
 * the fake sink's equivalent), never on elapsed wall-clock time. A generation guards every
 * render/write boundary so a seek cannot publish stale PCM.
 */
internal class EditorPreviewEngine(
    private val renderer: TimelineRenderer,
    private val sinkFactory: PreviewAudioSinkFactory = PreviewAudioSinkFactory {
        AndroidPreviewAudioSink()
    },
    private val renderDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + renderDispatcher)
    private val generation = AtomicLong(0L)
    private val lock = Any()
    private val _state = MutableStateFlow(EditorPlaybackState())
    val state: StateFlow<EditorPlaybackState> = _state.asStateFlow()

    private var session: EditorSession? = null
    private var sink: PreviewAudioSink? = null
    private var renderJob: Job? = null
    private var renderCursorFrame = 0L
    private var baseFrame = 0L
    private var durationFrames = 0L
    private var released = false

    fun load(newSession: EditorSession) {
        synchronized(lock) {
            check(!released) { "EditorPreviewEngine is released" }
            val wasPlaying = _state.value.playing
            generation.incrementAndGet()
            renderJob?.cancel()
            renderJob = null
            renderer.invalidate()
            val nextSink = sink ?: sinkFactory.create().also { sink = it }
            runCatching { nextSink.pause() }
            runCatching { nextSink.flush() }
            session = newSession
            durationFrames = sessionDurationMs(newSession) * EDITOR_SAMPLE_RATE / 1_000L
            baseFrame = newSession.playheadMs.coerceIn(0L, sessionDurationMs(newSession)) *
                EDITOR_SAMPLE_RATE / 1_000L
            renderCursorFrame = baseFrame
            _state.value = EditorPlaybackState(
                playing = wasPlaying,
                positionMs = framesToMs(baseFrame),
                durationMs = framesToMs(durationFrames),
            )
            if (wasPlaying) {
                nextSink.play()
                renderJob = scope.launch { renderLoop(generation.get()) }
            }
        }
    }

    fun play() {
        synchronized(lock) {
            if (released || session == null || _state.value.playing) return
            if (durationFrames == 0L) return
            val nextSink = sink ?: sinkFactory.create().also { sink = it }
            nextSink.play()
            _state.value = _state.value.copy(playing = true, error = null)
            renderJob?.cancel()
            renderJob = scope.launch { renderLoop(generation.get()) }
        }
    }

    fun pause() {
        synchronized(lock) {
            if (released) return
            updatePositionFromSink()
            _state.value = _state.value.copy(playing = false)
            runCatching { sink?.pause() }
            renderJob?.cancel()
            renderJob = null
        }
    }

    fun seekTo(positionMs: Long) {
        synchronized(lock) {
            if (released) return
            val wasPlaying = _state.value.playing
            generation.incrementAndGet()
            renderJob?.cancel()
            renderJob = null
            runCatching { sink?.pause() }
            runCatching { sink?.flush() }
            renderer.invalidate()
            val targetMs = positionMs.coerceIn(0L, framesToMs(durationFrames))
            baseFrame = targetMs * EDITOR_SAMPLE_RATE / 1_000L
            renderCursorFrame = baseFrame
            _state.value = _state.value.copy(
                playing = wasPlaying,
                positionMs = framesToMs(baseFrame),
                error = null,
            )
            if (wasPlaying) {
                sink?.play()
                renderJob = scope.launch { renderLoop(generation.get()) }
            }
        }
    }

    /** Drops queued PCM after a source/timeline mutation while retaining the current playhead. */
    fun invalidateFrom(sourceIds: Set<String> = emptySet()) {
        synchronized(lock) {
            if (released) return
            val wasPlaying = _state.value.playing
            generation.incrementAndGet()
            renderJob?.cancel()
            renderJob = null
            updatePositionFromSink()
            baseFrame = _state.value.positionMs * EDITOR_SAMPLE_RATE / 1_000L
            runCatching { sink?.pause() }
            runCatching { sink?.flush() }
            renderer.invalidate(sourceIds)
            renderCursorFrame = baseFrame
            if (wasPlaying) {
                sink?.play()
                renderJob = scope.launch { renderLoop(generation.get()) }
            }
        }
    }

    fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            generation.incrementAndGet()
            renderJob?.cancel()
            renderJob = null
            runCatching { sink?.pause() }
            runCatching { sink?.flush() }
            runCatching { sink?.stop() }
            runCatching { sink?.close() }
            sink = null
            runCatching { renderer.close() }
            scope.cancel()
            _state.value = _state.value.copy(playing = false)
        }
    }

    private suspend fun renderLoop(localGeneration: Long) {
        try {
            while (true) {
                val next = synchronized(lock) {
                    if (released || localGeneration != generation.get() || !_state.value.playing) {
                        return
                    }
                    if (renderCursorFrame >= durationFrames) {
                        updatePositionFromSink()
                        _state.value = _state.value.copy(playing = false)
                        sink?.pause()
                        return
                    }
                    val currentSink = sink ?: return
                    val queued = currentSink.queuedFrames()
                    if (queued >= TARGET_QUEUE_FRAMES) null else {
                        val count = minOf(RENDER_CHUNK_FRAMES.toLong(), durationFrames - renderCursorFrame).toInt()
                        renderCursorFrame to count
                    }
                }
                if (next == null) {
                    delay(10L)
                    continue
                }

                val (startFrame, frameCount) = next
                val pcm = renderer.render(checkNotNull(session), startFrame, frameCount)
                val accepted = synchronized(lock) {
                    if (released || localGeneration != generation.get() || !_state.value.playing) {
                        return
                    }
                    val currentSink = sink ?: return
                    val written = currentSink.write(pcm, 0, pcm.size)
                    if (written < 0) throw IllegalStateException("Audio preview write failed: $written")
                    renderCursorFrame += written.toLong()
                    updatePositionFromSink()
                    written
                }
                if (accepted == 0) delay(10L)
            }
        } catch (_: CancellationException) {
            // A seek, mutation, pause, or release owns cancellation and has already cleaned up.
        } catch (error: Throwable) {
            synchronized(lock) {
                if (localGeneration == generation.get() && !released) {
                    _state.value = _state.value.copy(playing = false, error = error.message)
                }
            }
        }
    }

    private fun updatePositionFromSink() {
        val playedFrame = sink?.playedFrames()?.coerceAtLeast(0L) ?: 0L
        val positionFrame = (baseFrame + playedFrame).coerceAtMost(durationFrames)
        _state.value = _state.value.copy(positionMs = framesToMs(positionFrame))
    }

    private fun framesToMs(frames: Long): Long = frames * 1_000L / EDITOR_SAMPLE_RATE

    private fun sessionDurationMs(session: EditorSession): Long = session.tracks
        .asSequence()
        .flatMap { it.clips.asSequence() }
        .map { it.timelineEndMs }
        .maxOrNull()
        ?.coerceAtLeast(0L)
        ?: 0L

    private companion object {
        const val RENDER_CHUNK_FRAMES = 960
        const val TARGET_QUEUE_FRAMES = EDITOR_SAMPLE_RATE * 3L
    }
}

/** Android sink kept behind the small [PreviewAudioSink] contract. */
private class AndroidPreviewAudioSink : PreviewAudioSink {
    private val track: AudioTrack
    private var acceptedFrames = 0L
    private var closed = false

    init {
        val minBuffer = AudioTrack.getMinBufferSize(
            EDITOR_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuffer > 0) { "AudioTrack returned an invalid minimum buffer size: $minBuffer" }
        val bufferSize = max(minBuffer, RENDER_BUFFER_BYTES)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(EDITOR_SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack failed to initialize" }
    }

    override fun play() = track.play()

    override fun pause() = track.pause()

    override fun stop() {
        if (!closed) runCatching { track.stop() }
    }

    override fun flush() {
        if (!closed) {
            runCatching { track.flush() }
            acceptedFrames = 0L
        }
    }

    override fun queuedFrames(): Long = (acceptedFrames - playedFrames()).coerceAtLeast(0L)

    override fun playedFrames(): Long = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL

    override fun write(samples: ShortArray, offset: Int, size: Int): Int {
        check(!closed) { "Audio preview sink is closed" }
        val written = track.write(samples, offset, size, AudioTrack.WRITE_BLOCKING)
        if (written < 0) throw IllegalStateException("AudioTrack.write failed: $written")
        acceptedFrames += written.toLong()
        return written
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    private companion object {
        const val RENDER_CHUNK_FRAMES = 960
        const val RENDER_BUFFER_BYTES = RENDER_CHUNK_FRAMES * 2 * 8
    }
}
