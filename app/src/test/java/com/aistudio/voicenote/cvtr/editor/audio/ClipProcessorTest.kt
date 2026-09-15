package com.aistudio.voicenote.cvtr.editor.audio

import com.aistudio.voicenote.cvtr.editor.model.AudioClip
import com.aistudio.voicenote.cvtr.editor.model.AudioSourceRef
import com.aistudio.voicenote.cvtr.editor.model.ClipEffects
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ClipProcessorTest {
    @Test
    fun `neutral processing keeps pcm values while fade reaches unity`() {
        val processor = ClipProcessor(ClipEffectProcessorFactory { _, _ ->
            error("neutral speed and pitch must bypass the effect processor")
        })
        val clip = clip(ClipEffects(fadeInMs = 100))

        val output = processor.process(clip, ShortArray(4_800) { 32_767 }, 0L)

        assertEquals(0f, output.first(), 0.001f)
        assertEquals(1f, output.last(), 0.02f)
        processor.close()
    }

    @Test
    fun `speed and pitch are passed independently to the effect seam`() {
        val calls = mutableListOf<Pair<Float, Float>>()
        val processor = ClipProcessor(ClipEffectProcessorFactory { speed, pitch ->
            calls += speed to pitch
            object : ClipEffectProcessor {
                override fun process(input: ShortArray, outputFrames: Int): ShortArray {
                    assertEquals(2, outputFrames)
                    return ShortArray(outputFrames) { 16_384 }
                }

                override fun close() = Unit
            }
        })

        val output = processor.process(
            clip(ClipEffects(speed = 2f, pitchSemitones = 3f)),
            ShortArray(4),
            0L,
        )

        assertEquals(listOf(2f to 3f), calls)
        assertEquals(2, output.size)
        assertFalse(output.any { it == 0f })
        processor.close()
    }

    private fun clip(effects: ClipEffects): AudioClip = AudioClip(
        id = "clip",
        source = AudioSourceRef("content://source", durationMs = 10_000L),
        sourceStartMs = 0L,
        sourceEndMs = 10_000L,
        timelineStartMs = 0L,
        effects = effects,
    )
}
