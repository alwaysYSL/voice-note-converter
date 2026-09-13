package com.aistudio.voicenote.cvtr.audio

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StorageMaintenanceTest {
    @Test
    fun `cleanup removes old managed cache but keeps recent files`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val oldFile = File(app.cacheDir, "voice_note_cleanup_old.ogg").apply {
            writeBytes(byteArrayOf(1))
        }
        val recentFile = File(app.cacheDir, "voice_note_cleanup_recent.ogg").apply {
            writeBytes(byteArrayOf(1))
        }
        val now = System.currentTimeMillis()
        oldFile.setLastModified(now - 10_000L)
        recentFile.setLastModified(now - 100L)
        try {
            StorageMaintenance.cleanup(
                context = app,
                nowMs = now,
                cacheRetentionMs = 1_000L,
                inputRetentionMs = 1_000L
            )

            assertFalse(oldFile.exists())
            assertTrue(recentFile.exists())
        } finally {
            oldFile.delete()
            recentFile.delete()
        }
    }
}
