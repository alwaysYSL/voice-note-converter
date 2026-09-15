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
import kotlinx.coroutines.cancelChildren
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
            if (released) return
            installSessionLocked(newSession, preservePosition = false)
        }
    }

    /** Replaces timeline audio while retaining the actual sink frame rather than a millisecond round-trip. */
    fun updateSession(newSession: EditorSession) {
        synchronized(lock) {
            if (released) return
            installSessionLocked(newSession, preservePosition = true)
        }
    }

    fun play() {
        synchronized(lock) {
            if (released || session == null || _state.value.playing) return
            if (durationFrames == 0L) return
            val nextSink = createSinkLocked() ?: return
            if (renderCursorFrame >= durationFrames) {
                generation.incrementAndGet()
                renderJob?.cancel()
                renderJob = null
                safePauseLocked()
                safeFlushLocked()
                safeInvalidateLocked()
                baseFrame = 0L
                renderCursorFrame = 0L
            }
            if (!safePlayLocked(nextSink)) return
            _state.value = _state.value.copy(playing = true, error = null)
            renderJob?.cancel()
            renderJob = scope.launch { renderLoop(generation.get()) }
        }
    }

    fun pause() {
        synchronized(lock) {
            if (released) return
            // Invalidate before cancelling/lifecycle calls: a render blocked outside this lock
            // must fail its generation check when it returns, even across pause -> play.
            generation.incrementAndGet()
            renderJob?.cancel()
            renderJob = null
            updatePositionFromSink()
            _state.value = _state.value.copy(playing = false)
            safePauseLocked()
        }
    }

    fun seekTo(positionMs: Long) {
        synchronized(lock) {
            if (released) return
            val wasPlaying = _state.value.playing
            generation.incrementAndGet()
            renderJob?.cancel()
            renderJob = null
            safePauseLocked()
            safeFlushLocked()
            safeInvalidateLocked()
            val targetMs = positionMs.coerceIn(0L, framesToMs(durationFrames))
            baseFrame = targetMs * EDITOR_SAMPLE_RATE / 1_000L
            renderCursorFrame = baseFrame
            _state.value = _state.value.copy(
                playing = wasPlaying,
                positionMs = framesToMs(baseFrame),
                error = null,
            )
            if (wasPlaying) {
                val nextSink = sink ?: createSinkLocked()
                if (nextSink == null || !safePlayLocked(nextSink)) return
                renderJob = scope.launch { renderLoop(generation.get()) }
            }
        }
    }

    /** Drops queued PCM after a source/timeline mutation while retaining the current playhead. */
    fun invalidateFrom(sourceIds: Set<String> = emptySet()) {
        synchronized(lock) {
            if (released) return
            val wasPlaying = _state.value.playing
            invalidateFromFrameLocked(currentPositionFrameLocked(), sourceIds, wasPlaying)
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
            scope.coroutineContext.cancelChildren()
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
                        val currentSink = sink ?: return
                        val playedThrough = baseFrame + currentSink.playedFrames().coerceAtLeast(0L)
                        val drained = currentSink.queuedFrames() <= 0L &&
                            playedThrough >= renderCursorFrame
                        if (!drained) null else {
                            updatePositionFromSink()
                            _state.value = _state.value.copy(playing = false)
                            currentSink.pause()
                            return
                        }
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
                renderer.consumeWarning()?.let { warning ->
                    synchronized(lock) {
                        if (!released && localGeneration == generation.get()) {
                            _state.value = _state.value.copy(error = warning)
                        }
                    }
                }
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
                    failSinkLocked(error)
                    _state.value = _state.value.copy(
                        playing = false,
                        error = error.message ?: error::class.simpleName ?: "Audio preview failed",
                    )
                }
            }
        }
    }

    private fun updatePositionFromSink() {
        val playedFrame = runCatching { sink?.playedFrames()?.coerceAtLeast(0L) ?: 0L }
            .getOrElse {
                reportErrorLocked(it)
                0L
            }
        val positionFrame = (baseFrame + playedFrame).coerceAtMost(durationFrames)
        _state.value = _state.value.copy(positionMs = framesToMs(positionFrame))
    }

    private fun installSessionLocked(newSession: EditorSession, preservePosition: Boolean) {
        val wasPlaying = _state.value.playing
        val preservedFrame = if (preservePosition) currentPositionFrameLocked() else 0L
        generation.incrementAndGet()
        renderJob?.cancel()
        renderJob = null
        safePauseLocked()
        safeFlushLocked()
        safeInvalidateLocked()
        session = newSession
        val durationMs = sessionDurationMs(newSession)
        durationFrames = durationMs * EDITOR_SAMPLE_RATE / 1_000L
        baseFrame = if (preservePosition) {
            preservedFrame.coerceIn(0L, durationFrames)
        } else {
            newSession.playheadMs.coerceIn(0L, durationMs) * EDITOR_SAMPLE_RATE / 1_000L
        }
        renderCursorFrame = baseFrame
        val setupError = _state.value.error
        _state.value = EditorPlaybackState(
            playing = false,
            positionMs = framesToMs(baseFrame),
            durationMs = framesToMs(durationFrames),
            error = setupError,
        )
        if (!wasPlaying) return
        val nextSink = sink ?: createSinkLocked() ?: return
        if (!safePlayLocked(nextSink)) return
        _state.value = _state.value.copy(playing = true)
        renderJob = scope.launch { renderLoop(generation.get()) }
    }

    private fun currentPositionFrameLocked(): Long {
        val playedFrame = runCatching { sink?.playedFrames()?.coerceAtLeast(0L) ?: 0L }
            .getOrElse {
                reportErrorLocked(it)
                0L
            }
        return (baseFrame + playedFrame).coerceIn(0L, durationFrames)
    }

    private fun invalidateFromFrameLocked(
        preservedFrame: Long,
        sourceIds: Set<String>,
        wasPlaying: Boolean,
    ) {
        generation.incrementAndGet()
        renderJob?.cancel()
        renderJob = null
        safePauseLocked()
        safeFlushLocked()
        safeInvalidateLocked(sourceIds)
        baseFrame = preservedFrame.coerceIn(0L, durationFrames)
        renderCursorFrame = baseFrame
        _state.value = _state.value.copy(
            playing = false,
            positionMs = framesToMs(baseFrame),
            error = null,
        )
        if (!wasPlaying) return
        val nextSink = sink ?: createSinkLocked()
        if (nextSink == null || !safePlayLocked(nextSink)) return
        _state.value = _state.value.copy(playing = true, error = null)
        renderJob = scope.launch { renderLoop(generation.get()) }
    }

    private fun createSinkLocked(): PreviewAudioSink? {
        sink?.let { return it }
        return try {
            sinkFactory.create().also { sink = it }
        } catch (error: Throwable) {
            reportErrorLocked(error)
            null
        }
    }

    private fun safePlayLocked(target: PreviewAudioSink): Boolean = try {
        target.play()
        true
    } catch (error: Throwable) {
        failSinkLocked(error)
        false
    }

    private fun safePauseLocked(): Boolean = sink?.let { target ->
        try {
            target.pause()
            true
        } catch (error: Throwable) {
            failSinkLocked(error)
            false
        }
    } ?: true

    private fun safeFlushLocked(): Boolean = sink?.let { target ->
        try {
            target.flush()
            true
        } catch (error: Throwable) {
            failSinkLocked(error)
            false
        }
    } ?: true

    private fun safeInvalidateLocked(sourceIds: Set<String> = emptySet()) {
        try {
            renderer.invalidate(sourceIds)
        } catch (error: Throwable) {
            reportErrorLocked(error)
        }
    }

    private fun failSinkLocked(error: Throwable) {
        runCatching { sink?.stop() }
        runCatching { sink?.close() }
        sink = null
        reportErrorLocked(error)
    }

    private fun reportErrorLocked(error: Throwable) {
        _state.value = _state.value.copy(
            playing = false,
            error = error.message ?: error::class.simpleName ?: "Audio preview failed",
        )
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
    private var track: AudioTrack? = null
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
        try {
            val created = AudioTrack.Builder()
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
            track = created
            check(created.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack failed to initialize" }
        } catch (error: Throwable) {
            runCatching { track?.release() }
            track = null
            throw error
        }
    }

    override fun play() = checkNotNull(track).play()

    override fun pause() = checkNotNull(track).pause()

    override fun stop() {
        if (!closed) checkNotNull(track).stop()
    }

    override fun flush() {
        if (!closed) {
            checkNotNull(track).flush()
            acceptedFrames = 0L
        }
    }

    override fun queuedFrames(): Long = (acceptedFrames - playedFrames()).coerceAtLeast(0L)

    override fun playedFrames(): Long = checkNotNull(track).playbackHeadPosition.toLong() and 0xFFFF_FFFFL

    override fun write(samples: ShortArray, offset: Int, size: Int): Int {
        check(!closed) { "Audio preview sink is closed" }
        val written = checkNotNull(track).write(samples, offset, size, AudioTrack.WRITE_BLOCKING)
        if (written < 0) throw IllegalStateException("AudioTrack.write failed: $written")
        acceptedFrames += written.toLong()
        return written
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
    }

    private companion object {
        const val RENDER_BUFFER_BYTES = EDITOR_SAMPLE_RATE * 3 * 2
    }
}
