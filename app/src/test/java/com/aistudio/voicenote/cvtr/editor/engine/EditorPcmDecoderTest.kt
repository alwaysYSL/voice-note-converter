package com.aistudio.voicenote.cvtr.editor.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
class EditorPcmDecoderTest {

    @Test
    fun testGenerateWaveformPointsFromPcm() {
        // Create 1 second of 48kHz mono 16-bit PCM (48000 samples = 96000 bytes)
        val tempFile = File.createTempFile("test_pcm", ".pcm")
        tempFile.deleteOnExit()

        val sampleCount = 48000
        val buffer = ByteBuffer.allocate(sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            val sample = (Math.sin(2.0 * Math.PI * 440.0 * i / 48000.0) * 16000.0).toInt().toShort()
            buffer.putShort(sample)
        }
        tempFile.writeBytes(buffer.array())

        val waveform = EditorPcmDecoder.extractWaveformFromPcm(tempFile, 100)
        assertEquals(100, waveform.size)
        assertTrue(waveform.all { it in 0f..1f })
        assertTrue(waveform.any { it > 0.1f })
    }

    @Test
    fun testResampleAndMixMono() {
        val stereo48k = ShortArray(480) { if (it % 2 == 0) 1000.toShort() else 2000.toShort() }
        val mono = EditorPcmDecoder.convertStereoToMono(stereo48k)
        assertEquals(240, mono.size)
        assertEquals(1500.toShort(), mono[0])
    }
}