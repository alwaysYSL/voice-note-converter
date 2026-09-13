package com.aistudio.voicenote.cvtr.audio

import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioProcessingTest {
    @Test
    fun `silence trim removes only leading and trailing runs`() {
        val effects = StreamingAudioEffects(
            options = AudioProcessingOptions(trimSilence = true),
            silenceThreshold = 10,
            minSilenceSamples = 2
        )

        val output = effects.process(shortArrayOf(0, 0, 100, 0, 0, 20, 0, 0)) + effects.finish()

        assertArrayEquals(shortArrayOf(100, 0, 0, 20), output)
    }

    @Test
    fun `normalization boosts quiet audio without clipping`() {
        val effects = StreamingAudioEffects(
            options = AudioProcessingOptions(normalizeAudio = true)
        )

        val output = effects.process(shortArrayOf(1_000, -2_000, 500)) + effects.finish()

        assertTrue(output.maxOf { abs(it.toInt()) } <= 32_767)
        assertTrue(abs(output[0].toInt()) > 1_000)
    }
}
