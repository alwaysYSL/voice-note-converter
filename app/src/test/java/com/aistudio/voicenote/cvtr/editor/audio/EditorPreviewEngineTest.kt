package com.aistudio.voicenote.cvtr.editor.audio

import com.aistudio.voicenote.cvtr.editor.model.EditorSession
import com.aistudio.voicenote.cvtr.editor.model.AudioClip
import com.aistudio.voicenote.cvtr.editor.model.AudioSourceRef
import com.aistudio.voicenote.cvtr.editor.model.EditorTrack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorPreviewEngineTest {
    @Test
    fun `play and seek discard old generation and expose played position`() = runTest {
        val renderer = RecordingRenderer()
        val sink = DeterministicSink()
        val engine = EditorPreviewEngine(
            renderer = renderer,
            sinkFactory = PreviewAudioSinkFactory { sink },
            renderDispatcher = StandardTestDispatcher(testScheduler),
        )
        val session = EditorSession(
            id = "session",
            tracks = listOf(
                EditorTrack(
                    id = "track",
                    name = "Track",
                    clips = listOf(
                        AudioClip(
                            id = "clip",
                            source = AudioSourceRef("fake", 200_000L),
                            sourceStartMs = 0L,
                            sourceEndMs = 200_000L,
                            timelineStartMs = 0L,
                        ),
                    ),
                ),
            ),
        )

        engine.load(session)
        engine.play()
        runCurrent()
        assertTrue(engine.state.value.playing)
        assertEquals(20L, engine.state.value.positionMs)

        engine.seekTo(120_000L)
        assertEquals(120_000L, engine.state.value.positionMs)
        runCurrent()

        assertTrue(renderer.renderStarts.contains(120_000L * EDITOR_SAMPLE_RATE / 1_000L))
        assertEquals(1, sink.flushCount)
        engine.release()
    }

    @Test
    fun `seek generation drops blocked render before it can write`() {
        val renderer = BlockingRenderer()
        val sink = RecordingSink()
        val engine = EditorPreviewEngine(
            renderer = renderer,
            sinkFactory = PreviewAudioSinkFactory { sink },
            renderDispatcher = Dispatchers.Default,
        )
        engine.load(sessionWithDuration(200_000L))
        engine.play()

        assertTrue(renderer.started.await(5, TimeUnit.SECONDS))
        engine.seekTo(120_000L)
        renderer.release.countDown()
        assertTrue(sink.wrote.await(5, TimeUnit.SECONDS))

        assertTrue(sink.markers.none { it == 1 })
        assertTrue(sink.markers.any { it == 2 })
        engine.release()
    }

    @Test
    fun `eos drains before stopping replay restarts and sink failure reaches state`() = runTest {
        val renderer = RecordingRenderer()
        val sink = DrainableSink()
        val engine = EditorPreviewEngine(
            renderer = renderer,
            sinkFactory = PreviewAudioSinkFactory { sink },
            renderDispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            engine.load(sessionWithDuration(20L))
            engine.play()
            runCurrent()

            assertTrue(engine.state.value.playing)
            assertTrue(sink.maxQueuedFrames <= 144_000L)
            sink.drain()
            advanceTimeBy(10L)
            runCurrent()
            assertFalse(engine.state.value.playing)
            assertEquals(20L, engine.state.value.positionMs)

            engine.play()
            runCurrent()
            assertEquals(0L, renderer.renderStarts[2])
        } finally {
            engine.release()
        }

        val failingEngine = EditorPreviewEngine(
            renderer = RecordingRenderer(),
            sinkFactory = PreviewAudioSinkFactory { FailingSink() },
            renderDispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            failingEngine.load(sessionWithDuration(20L))
            failingEngine.play()
            runCurrent()
            assertNotNull(failingEngine.state.value.error)
        } finally {
            failingEngine.release()
        }
    }

    private class RecordingRenderer : TimelineRenderer {
        val renderStarts = mutableListOf<Long>()

        override fun render(session: EditorSession, startFrame: Long, frameCount: Int): ShortArray {
            renderStarts += startFrame
            return ShortArray(frameCount) { startFrame.toInt().toShort() }
        }

        override fun invalidate(sourceIds: Set<String>) = Unit

        override fun close() = Unit
    }

    private class BlockingRenderer : TimelineRenderer {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun render(session: EditorSession, startFrame: Long, frameCount: Int): ShortArray {
            if (startFrame == 0L) {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "blocked render was not released" }
            }
            val marker = if (startFrame == 0L) 1 else 2
            return ShortArray(frameCount) { marker.toShort() }
        }

        override fun invalidate(sourceIds: Set<String>) = Unit
        override fun close() = Unit
    }

    private class DeterministicSink : PreviewAudioSink {
        var flushCount = 0
        private var played = 0L
        private var accepted = 0L

        override fun play() = Unit
        override fun pause() = Unit
        override fun stop() = Unit
        override fun flush() {
            flushCount++
            played = 0L
            accepted = 0L
        }

        override fun queuedFrames(): Long = if (accepted == 0L) 0L else 144_000L
        override fun playedFrames(): Long = played
        override fun write(samples: ShortArray, offset: Int, size: Int): Int {
            accepted += size
            played += size
            return size
        }

        override fun close() = Unit
    }

    private class RecordingSink : PreviewAudioSink {
        val wrote = CountDownLatch(1)
        val markers = mutableListOf<Int>()
        private var played = 0L
        private var accepted = 0L

        override fun play() = Unit
        override fun pause() = Unit
        override fun stop() = Unit
        override fun flush() {
            played = 0L
            accepted = 0L
        }

        override fun queuedFrames(): Long = if (accepted == 0L) 0L else 144_000L
        override fun playedFrames(): Long = played
        override fun write(samples: ShortArray, offset: Int, size: Int): Int {
            markers += samples[offset].toInt()
            accepted += size
            played += size
            wrote.countDown()
            return size
        }

        override fun close() = Unit
    }

    private class DrainableSink : PreviewAudioSink {
        var maxQueuedFrames = 0L
        private var played = 0L
        private var accepted = 0L

        override fun play() = Unit
        override fun pause() = Unit
        override fun stop() = Unit
        override fun flush() {
            played = 0L
            accepted = 0L
        }

        override fun queuedFrames(): Long = (accepted - played).coerceAtLeast(0L)
        override fun playedFrames(): Long = played
        override fun write(samples: ShortArray, offset: Int, size: Int): Int {
            accepted += size
            maxQueuedFrames = maxOf(maxQueuedFrames, queuedFrames())
            return size
        }

        fun drain() {
            played = accepted
        }

        override fun close() = Unit
    }

    private class FailingSink : PreviewAudioSink {
        override fun play() = Unit
        override fun pause() = Unit
        override fun stop() = Unit
        override fun flush() = Unit
        override fun queuedFrames(): Long = 0L
        override fun playedFrames(): Long = 0L
        override fun write(samples: ShortArray, offset: Int, size: Int): Int =
            error("write-failed")

        override fun close() = Unit
    }

    private fun sessionWithDuration(durationMs: Long): EditorSession = EditorSession(
        id = "session-$durationMs",
        tracks = listOf(
            EditorTrack(
                id = "track",
                name = "Track",
                clips = listOf(
                    AudioClip(
                        id = "clip",
                        source = AudioSourceRef("fake", durationMs),
                        sourceStartMs = 0L,
                        sourceEndMs = durationMs,
                        timelineStartMs = 0L,
                    ),
                ),
            ),
        ),
    )
}
