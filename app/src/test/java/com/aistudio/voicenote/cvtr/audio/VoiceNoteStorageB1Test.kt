package com.aistudio.voicenote.cvtr.audio

import android.app.Application
import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceNoteStorageB1Test {
    @Test
    fun `fileExists detects an existing file uri`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val file = File(app.cacheDir, "existing_file_uri.ogg").apply {
            writeBytes(byteArrayOf(1))
        }

        assertTrue(VoiceNoteStorage.fileExists(app, file.toURI().toString()))
    }

    @Test
    fun `fileExists detects an existing legacy raw path`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val file = File(app.cacheDir, "existing_raw_path.ogg").apply {
            writeBytes(byteArrayOf(1))
        }

        assertTrue(VoiceNoteStorage.fileExists(app, file.absolutePath))
    }

    @Test
    fun `fileExists detects an accessible content uri`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val uri = app.contentResolver.insert(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "existing_content_uri.ogg")
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/ogg")
            }
        ) ?: error("Robolectric MediaStore insert failed")
        app.contentResolver.openOutputStream(uri)?.use { it.write(byteArrayOf(1)) }

        assertTrue(VoiceNoteStorage.fileExists(app, uri.toString()))
    }
}
