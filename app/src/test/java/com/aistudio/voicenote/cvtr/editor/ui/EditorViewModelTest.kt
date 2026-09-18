package com.aistudio.voicenote.cvtr.editor.ui

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.aistudio.voicenote.cvtr.data.local.AppDatabase
import com.aistudio.voicenote.cvtr.data.repository.ConversionHistoryRepository
import com.aistudio.voicenote.cvtr.editor.model.AudioTrackClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class EditorViewModelTest {

    private lateinit var application: Application
    private lateinit var repository: ConversionHistoryRepository
    private lateinit var viewModel: EditorViewModel

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ConversionHistoryRepository(db.conversionHistoryDao())
        viewModel = EditorViewModel(application, repository)
    }

    @Test
    fun testTrackManipulation() {
        val tempFile = File.createTempFile("test_clip", ".pcm")
        tempFile.deleteOnExit()

        val clip = AudioTrackClip(
            sourceUri = Uri.parse("content://test/1"),
            displayName = "Voice 1",
            pcmCacheFile = tempFile,
            durationMs = 5000L
        )

        // Set track 0 directly for test
        viewModel.setTrackForTesting(0, clip)
        val state = viewModel.timelineState.value
        assertNotNull(state.tracks[0])
        assertEquals(5000L, state.totalDurationMs)

        // Update pitch
        viewModel.updateClipPitch(0, 3f)
        assertEquals(3f, viewModel.timelineState.value.tracks[0]?.pitchSemitones)

        // Update volume
        viewModel.updateClipVolume(0, 1.5f)
        assertEquals(1.5f, viewModel.timelineState.value.tracks[0]?.volumeGain)

        // Update offset
        viewModel.updateClipOffset(0, 2000L)
        assertEquals(2000L, viewModel.timelineState.value.tracks[0]?.startOffsetMs)
        assertEquals(7000L, viewModel.timelineState.value.totalDurationMs)

        // Swap track 0 and track 2
        viewModel.swapTracks(0, 2)
        assertNull(viewModel.timelineState.value.tracks[0])
        assertNotNull(viewModel.timelineState.value.tracks[2])

        // Remove track
        viewModel.removeTrack(2)
        assertNull(viewModel.timelineState.value.tracks[2])
        assertEquals(0L, viewModel.timelineState.value.totalDurationMs)
    }
}