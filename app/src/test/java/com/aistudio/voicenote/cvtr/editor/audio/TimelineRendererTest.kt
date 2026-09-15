package com.aistudio.voicenote.cvtr.editor.audio

import com.aistudio.voicenote.cvtr.editor.model.AudioClip
import com.aistudio.voicenote.cvtr.editor.model.AudioSourceRef
import com.aistudio.voicenote.cvtr.editor.model.EditorSession
import com.aistudio.voicenote.cvtr.editor.model.EditorTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineRendererTest {
    @Test
    fun `renderer mixes only clips active in requested window`() {
        val opened = mutableListOf<String>()
        val factory = PcmSourceReaderFactory { source ->
            opened += source.uri
            object : PcmSourceReader {
                override val sampleRate: Int = EDITOR_SAMPLE_RATE

                override fun read(sourceFrame: Long, frameCount: Int): ShortArray =
                    ShortArray(frameCount) { if (source.uri == "voice") 8_000 else 4_000 }

                override fun close() = Unit
            }
        }
        val renderer = DefaultTimelineRenderer(factory)
        val session = EditorSession(
            id = "session",
            tracks = listOf(
                EditorTrack(
                    id = "voice-track",
                    name = "Voice",
                    clips = listOf(clip("voice-clip", "voice", 1_000L)),
                ),
                EditorTrack(
                    id = "music-track",
                    name = "Music",
                    clips = listOf(clip("music-clip", "music", 1_000L)),
                ),
                EditorTrack(
                    id = "inactive-track",
                    name = "Inactive",
                    clips = listOf(clip("inactive-clip", "inactive", 3_000L)),
                ),
            ),
        )

        val pcm = renderer.render(session, startFrame = 48_000L, frameCount = 1_920)

        assertEquals(setOf("voice", "music"), opened.toSet())
        assertEquals(1_920, pcm.size)
        assertTrue(pcm.take(960).all { it.toInt() == 12_000 })
        assertTrue(pcm.drop(960).all { it.toInt() == 0 })
        renderer.close()

        val fractionalClip = AudioClip(
            id = "fractional-clip",
            source = AudioSourceRef("fractional", durationMs = 100L),
            sourceStartMs = 0L,
            sourceEndMs = 100L,
            timelineStartMs = 0L,
            effects = com.aistudio.voicenote.cvtr.editor.model.ClipEffects(speed = 1.3f),
        )
        val fractionalRenderer = DefaultTimelineRenderer(
            sourceFactory = factory,
            clipProcessor = ClipProcessor(ClipEffectProcessorFactory { _, _ ->
                object : ClipEffectProcessor {
                    override fun process(input: ShortArray, outputFrames: Int): ShortArray =
                        ShortArray(outputFrames) { input.getOrElse(it) { 0 } }

                    override fun close() = Unit
                }
            }),
        )
        val fractional = fractionalRenderer.render(
            EditorSession(
                id = "fractional-session",
                tracks = listOf(EditorTrack("fractional-track", "Fractional", clips = listOf(fractionalClip))),
            ),
            startFrame = 0L,
            frameCount = 4_000,
        )
        val modelFrames = fractionalClip.timelineEndMs * EDITOR_SAMPLE_RATE / 1_000L
        assertTrue(fractional.take(modelFrames.toInt()).any { it.toInt() != 0 })
        assertTrue(fractional.drop(modelFrames.toInt()).all { it.toInt() == 0 })
        fractionalRenderer.close()
    }

    private fun clip(id: String, source: String, timelineStartMs: Long): AudioClip = AudioClip(
        id = id,
        source = AudioSourceRef(source, durationMs = 20L),
        sourceStartMs = 0L,
        sourceEndMs = 20L,
        timelineStartMs = timelineStartMs,
    )
}
