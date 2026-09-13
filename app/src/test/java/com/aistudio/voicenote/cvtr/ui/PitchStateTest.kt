package com.aistudio.voicenote.cvtr.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PitchStateTest {
    @Test
    fun `pitch state defaults to zero`() {
        val viewModel = MainViewModel(ApplicationProvider.getApplicationContext<Application>())
        try {
            assertEquals(0f, readPitch(viewModel))
        } finally {
            viewModel.audioPlayer.release()
        }
    }

    @Test
    fun `pitch state clamps to four semitones`() {
        val viewModel = MainViewModel(ApplicationProvider.getApplicationContext<Application>())
        try {
            invokeUpdatePitch(viewModel, 4.8f)
            assertEquals(4f, readPitch(viewModel))
        } finally {
            viewModel.audioPlayer.release()
        }
    }

    @Test
    fun `pitch state snaps values close to zero`() {
        val viewModel = MainViewModel(ApplicationProvider.getApplicationContext<Application>())
        try {
            invokeUpdatePitch(viewModel, 0.1f)
            assertEquals(0f, readPitch(viewModel))
        } finally {
            viewModel.audioPlayer.release()
        }
    }

    private fun readPitch(viewModel: MainViewModel): Float =
        MainUiState::class.java.getDeclaredField("pitchSemitones").let { field ->
            field.isAccessible = true
            field.getFloat(viewModel.uiState.value)
        }

    private fun invokeUpdatePitch(viewModel: MainViewModel, value: Float) {
        MainViewModel::class.java
            .getDeclaredMethod("updatePitch", Float::class.javaPrimitiveType)
            .invoke(viewModel, value)
    }
}
