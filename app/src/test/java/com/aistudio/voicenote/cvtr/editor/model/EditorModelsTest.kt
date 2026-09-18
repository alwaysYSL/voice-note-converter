package com.aistudio.voicenote.cvtr.editor.model

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class EditorModelsTest {

    @Test
    fun testAudioTrackClipCalculations() {
        val clip = AudioTrackClip(
            sourceUri = Uri.parse("content://media/audio/1"),
            displayName = "VoiceNote.ogg",
            pcmCacheFile = File("/tmp/pcm1"),
            durationMs = 10000L,
            startOffsetMs = 2000L,
            trimStartMs = 1000L,
            trimEndMs = 6000L,
            pitchSemitones = 2f,
            volumeGain = 1.2f
        )

        assertEquals(5000L, clip.activeDurationMs)
        assertEquals(7000L, clip.timelineEndMs)
    }

    @Test
    fun testEditorTimelineStateTotalDuration() {
        val clip1 = AudioTrackClip(
            sourceUri = Uri.parse("content://media/audio/1"),
            displayName = "Track1",
            pcmCacheFile = File("/tmp/pcm1"),
            durationMs = 5000L,
            startOffsetMs = 0L,
            trimStartMs = 0L,
            trimEndMs = 5000L
        )
        val clip2 = AudioTrackClip(
            sourceUri = Uri.parse("content://media/audio/2"),
            displayName = "Track2",
            pcmCacheFile = File("/tmp/pcm2"),
            durationMs = 4000L,
            startOffsetMs = 3000L,
            trimStartMs = 0L,
            trimEndMs = 4000L
        )

        val state = EditorTimelineState(
            tracks = listOf(clip1, clip2, null)
        )

        assertTrue(state.hasActiveTracks)
        assertEquals(7000L, state.totalDurationMs)
    }
}