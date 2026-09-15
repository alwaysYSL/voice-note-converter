package com.aistudio.voicenote.cvtr.editor.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class TrackMixerTest {
    @Test
    fun `mix applies volume and mute while summing active tracks`() {
        val output = TrackMixer().mix(
            chunks = listOf(
                TrackChunk(floatArrayOf(0.25f, 0.5f), volume = 1.5f, muted = false),
                TrackChunk(floatArrayOf(0.75f, 0.25f), volume = 1f, muted = true),
                TrackChunk(floatArrayOf(0.5f, 0.25f), volume = 0.5f, muted = false),
            ),
            frameCount = 2,
        )

        assertArrayEquals(floatArrayOf(0.625f, 0.875f), output, 0.0001f)
    }
}
