package com.aistudio.voicenote.cvtr.audio

import android.media.AudioFormat
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `decoder converts little endian float pcm and clamps invalid samples`() {
        val buffer = ByteBuffer.allocate(16)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                putFloat(0f)
                putFloat(1.5f)
                putFloat(-1f)
                putFloat(Float.NaN)
                flip()
            }

        val decoded = PcmDecoder.decode(
            outputBuffer = buffer,
            offset = 0,
            size = buffer.remaining(),
            encoding = AudioFormat.ENCODING_PCM_FLOAT,
            channels = 1
        )

        assertEquals(listOf(0, Short.MAX_VALUE.toInt(), -32_767, 0), decoded.map { it.toInt() })
    }

    @Test
    fun `decoder rejects unsupported pcm encodings`() {
        val error = runCatching {
            PcmDecoder.decode(
                outputBuffer = ByteBuffer.allocate(4),
                offset = 0,
                size = 4,
                encoding = AudioFormat.ENCODING_PCM_8BIT,
                channels = 1
            )
        }.exceptionOrNull()

        assertTrue(error is UnsupportedPcmEncodingException)
    }

    @Test
    fun `trim progress is relative to the selected window`() {
        assertEquals(
            0f,
            trimProgressFraction(
                presentationTimeUs = 70_000_000L,
                trimStartUs = 70_000_000L,
                trimEndUs = 80_000_000L,
                durationUs = 100_000_000L
            ),
            0f
        )
        assertEquals(
            0.5f,
            trimProgressFraction(
                presentationTimeUs = 75_000_000L,
                trimStartUs = 70_000_000L,
                trimEndUs = 80_000_000L,
                durationUs = 100_000_000L
            ),
            0.001f
        )
        assertEquals(
            1f,
            trimProgressFraction(
                presentationTimeUs = 80_000_000L,
                trimStartUs = 70_000_000L,
                trimEndUs = 80_000_000L,
                durationUs = 100_000_000L
            ),
            0f
        )
    }

    @Test
    fun `waveform accumulation remains bounded and returns telegram bars`() {
        val accumulator = WaveformAccumulator(targetBars = 4, expectedSamples = 8)
        accumulator.add(ShortArray(8) { 1_000 })

        val waveform = accumulator.result()

        assertEquals(4, waveform.size)
        assertEquals(listOf(31, 31, 31, 31), waveform)
    }

    @Test
    fun `trim boundary fader fades both edges`() {
        val fader = PcmBoundaryFader(fadeSamples = 2)

        val middle = fader.process(ShortArray(4) { 1_000 })
        val tail = fader.finish()

        assertEquals(listOf(500, 1_000), middle.map { it.toInt() })
        assertEquals(listOf(1_000, 500), tail.map { it.toInt() })
    }

    @Test
    fun `waveform codec emits bounded json and reads legacy list format`() {
        val encoded = WaveformCodec.encode(List(240) { 40 })

        assertEquals(200, WaveformCodec.decode(encoded).size)
        assertEquals(listOf(31, 0), WaveformCodec.decode("[40, -2]").take(2))
    }
}
