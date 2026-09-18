package com.aistudio.voicenote.cvtr.editor.engine

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.aistudio.voicenote.cvtr.editor.model.AudioTrackClip
import com.aistudio.voicenote.cvtr.editor.model.EditorTimelineState
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
class EditorExporterTest {

    @Test
    fun testExportCalculationAndValidation() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Create 1000ms test PCM
        val tempFile = File.createTempFile("test_export", ".pcm")
        tempFile.deleteOnExit()

        val sampleCount = 48000
        val buffer = ByteBuffer.allocate(sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            buffer.putShort(1000.toShort())
        }
        tempFile.writeBytes(buffer.array())

        val clip = AudioTrackClip(
            sourceUri = Uri.parse("content://test"),
            displayName = "VoiceNote1",
            pcmCacheFile = tempFile,
            durationMs = 1000L,
            startOffsetMs = 0L,
            trimStartMs = 0L,
            trimEndMs = 1000L
        )

        val state = EditorTimelineState(
            tracks = listOf(clip, null, null)
        )

        assertEquals(1000L, state.totalDurationMs)
        assertTrue(state.hasActiveTracks)
    }
}