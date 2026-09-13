package com.aistudio.voicenote.cvtr.ui

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainViewModelFeatureTest {
    @Test
    fun `output name and processing toggles are retained before conversion`() {
        val viewModel = MainViewModel(
            ApplicationProvider.getApplicationContext<Application>()
        )
        try {
            viewModel.updateOutputFileName("meeting.ogg")
            viewModel.setNormalizeAudio(true)
            viewModel.setTrimSilence(true)

            assertEquals("meeting.ogg", viewModel.uiState.value.outputFileName)
            assertTrue(viewModel.uiState.value.normalizeAudio)
            assertTrue(viewModel.uiState.value.trimSilence)
        } finally {
            viewModel.audioPlayer.release()
        }
    }

    @Test
    fun `A-B state selects matching uri and waveform`() {
        val original = Uri.parse("content://media/original")
        val converted = Uri.parse("content://media/converted")
        val originalState = MainUiState(
            selectedFileUri = original,
            convertedUri = converted,
            originalWaveform = listOf(1),
            convertedWaveform = listOf(2),
            previewSource = PreviewSource.ORIGINAL
        )

        assertEquals(original, originalState.previewUri())
        assertEquals(listOf(1), originalState.previewWaveform())

        val convertedState = originalState.copy(previewSource = PreviewSource.CONVERTED)
        assertEquals(converted, convertedState.previewUri())
        assertEquals(listOf(2), convertedState.previewWaveform())
    }
}
