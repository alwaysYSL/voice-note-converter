package com.example.audio

import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceNoteConverterB4Test {
    @Test
    fun `missing encoder delay uses standard Opus pre skip`() {
        assertEquals(312, opusPreSkipSamples(MediaFormat()))
    }

    @Test
    fun `out of range encoder delay uses standard Opus pre skip`() {
        val format = MediaFormat().apply {
            setInteger("encoder-delay", 65_536)
        }

        assertEquals(312, opusPreSkipSamples(format))
    }

    @Test
    fun `valid encoder delay metadata remains unchanged`() {
        val format = MediaFormat().apply {
            setInteger("encoder-delay", 480)
        }

        assertEquals(480, opusPreSkipSamples(format))
    }
}
