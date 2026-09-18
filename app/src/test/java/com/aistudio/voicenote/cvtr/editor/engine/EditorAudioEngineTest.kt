package com.aistudio.voicenote.cvtr.editor.engine

import android.net.Uri
import com.aistudio.voicenote.cvtr.editor.model.AudioTrackClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
class EditorAudioEngineTest {

    @Test
    fun testSoftLimiterClamping() {
        val overflowSums = intArrayOf(-40000, -32768, 0, 32767, 50000)
        val clamped = EditorAudioEngine.applySoftLimiter(overflowSums)

        assertEquals(Short.MIN_VALUE, clamped[0])
        assertEquals(Short.MIN_VALUE, clamped[1])
        assertEquals(0.toShort(), clamped[2])
        assertEquals(Short.MAX_VALUE, clamped[3])
        assertEquals(Short.MAX_VALUE, clamped[4])
    }

    @Test
    fun testReadTrackPcmSliceWithTrimAndOffset() {
        // Create 2000ms test PCM (96000 samples)
        val tempFile = File.createTempFile("test_slice", ".pcm")
        tempFile.deleteOnExit()

        val sampleCount = 96000 // 2 seconds at 48kHz
        val buffer = ByteBuffer.allocate(sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            buffer.putShort(1000.toShort())
        }
        tempFile.writeBytes(buffer.array())

        val clip = AudioTrackClip(
            sourceUri = Uri.parse("content://test"),
            displayName = "TestClip",
            pcmCacheFile = tempFile,
            durationMs = 2000L,
            startOffsetMs = 1000L, // starts at 1000ms in timeline
            trimStartMs = 500L,    // trimmed 500ms from start
            trimEndMs = 1500L,     // trimmed at 1500ms (active duration = 1000ms)
            pitchSemitones = 0f,
            volumeGain = 0.5f      // 50% volume -> sample 1000 becomes 500
        )

        // Request at 0ms (before clip starts): should be silence (0)
        val sliceBefore = EditorAudioEngine.readTrackPcmSlice(clip, timelinePositionMs = 500L, durationMs = 20L)
        assertTrue(sliceBefore.all { it == 0.toShort() })

        // Request at 1200ms (within clip): should be active and scaled to 500
        val sliceDuring = EditorAudioEngine.readTrackPcmSlice(clip, timelinePositionMs = 1200L, durationMs = 20L)
        assertTrue(sliceDuring.isNotEmpty())
        assertEquals(500.toShort(), sliceDuring[0])

        // Request at 2500ms (after clip ends): should be silence (0)
        val sliceAfter = EditorAudioEngine.readTrackPcmSlice(clip, timelinePositionMs = 2500L, durationMs = 20L)
        assertTrue(sliceAfter.all { it == 0.toShort() })
    }
}