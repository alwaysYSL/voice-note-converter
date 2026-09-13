package com.example.audio

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackWindowTest {
    @Test
    fun `trim preview starts and stops at the selected range`() {
        val window = PlaybackWindow.fromTrim(1_200L, 3_000L)

        assertEquals(1_200L, window.startMs)
        assertEquals(3_000L, window.endMs)
        assertFalse(window.hasReachedEnd(2_999L))
        assertTrue(window.hasReachedEnd(3_000L))
    }

    @Test
    fun `player loads the selected trim window before preview playback starts`() {
        val preview = AudioPreviewPlayer(
            ApplicationProvider.getApplicationContext<Application>(),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
        try {
            preview.setClipping(1_200L, 3_000L)
            preview.play(Uri.parse("content://example/audio"))

            val playbackWindow = AudioPreviewPlayer::class.java
                .getDeclaredField("playbackWindow")
                .apply { isAccessible = true }
                .get(preview) as PlaybackWindow
            assertEquals(PlaybackWindow.fromTrim(1_200L, 3_000L), playbackWindow)
        } finally {
            preview.release()
        }
    }
}
