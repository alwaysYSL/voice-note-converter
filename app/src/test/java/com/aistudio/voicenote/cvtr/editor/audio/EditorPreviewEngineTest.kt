package com.aistudio.voicenote.cvtr.editor.audio

import com.aistudio.voicenote.cvtr.editor.model.EditorSession
import com.aistudio.voicenote.cvtr.editor.model.AudioClip
import com.aistudio.voicenote.cvtr.editor.model.AudioSourceRef
import com.aistudio.voicenote.cvtr.editor.model.EditorTrack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
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
        assertEquals(2, sink.flushCount)
        engine.release()
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
}
