package com.aistudio.voicenote.cvtr.audio

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PitchPreviewPlayerTest {
    @Test
    fun `preview pitch updates player playback parameters`() {
        val preview = AudioPreviewPlayer(
            ApplicationProvider.getApplicationContext<Application>(),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
        try {
            AudioPreviewPlayer::class.java
                .getDeclaredMethod("setPitchPreview", Float::class.javaPrimitiveType)
                .invoke(preview, 1.5f)

            val player = AudioPreviewPlayer::class.java
                .getDeclaredField("player")
                .let { field ->
                    field.isAccessible = true
                    field.get(preview) as ExoPlayer
                }
            assertEquals(1.5f, player.playbackParameters.pitch)
        } finally {
            preview.release()
        }
    }

    @Test
    fun `setting pitch after starting a preview updates the selected pitch`() {
        val preview = AudioPreviewPlayer(
            ApplicationProvider.getApplicationContext<Application>(),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
        try {
            preview.play(Uri.parse("content://example/audio"))
            AudioPreviewPlayer::class.java
                .getDeclaredMethod("setPitchPreview", Float::class.javaPrimitiveType)
                .invoke(preview, 1.5f)

            val player = AudioPreviewPlayer::class.java
                .getDeclaredField("player")
                .let { field ->
                    field.isAccessible = true
                    field.get(preview) as ExoPlayer
                }
            assertEquals(1.5f, player.playbackParameters.pitch)
        } finally {
            preview.release()
        }
    }

    @Test
    fun `starting a new source clears the previous source pitch`() {
        val preview = AudioPreviewPlayer(
            ApplicationProvider.getApplicationContext<Application>(),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
        try {
            preview.play(Uri.parse("content://example/source"))
            AudioPreviewPlayer::class.java
                .getDeclaredMethod("setPitchPreview", Float::class.javaPrimitiveType)
                .invoke(preview, 1.5f)
            preview.play(Uri.parse("content://example/converted"))

            val player = AudioPreviewPlayer::class.java
                .getDeclaredField("player")
                .let { field ->
                    field.isAccessible = true
                    field.get(preview) as ExoPlayer
                }
            assertEquals(1f, player.playbackParameters.pitch)
        } finally {
            preview.release()
        }
    }
}
