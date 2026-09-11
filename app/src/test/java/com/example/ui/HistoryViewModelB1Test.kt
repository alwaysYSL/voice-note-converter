package com.example.ui

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.ConversionHistory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryViewModelB1Test {
    @Test
    fun `deleteItem removes history when legacy raw-path file is already missing`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val database = AppDatabase.getDatabase(app)
        val missingFile = File(app.filesDir, "externally_removed_${System.nanoTime()}.ogg")
        check(!missingFile.exists())
        val item = ConversionHistory(
            originalFileName = "meeting.m4a",
            outputFileName = missingFile.name,
            outputFilePath = missingFile.absolutePath,
            durationSeconds = 12,
            fileSizeBytes = 256,
            waveform = "[8,12,10]",
            bitrateKbps = 32,
            createdAt = System.currentTimeMillis()
        )
        val id = runBlocking { database.conversionHistoryDao().insert(item) }

        try {
            HistoryViewModel(app).deleteItem(item.copy(id = id))
            shadowOf(Looper.getMainLooper()).idle()

            val persisted = runBlocking { database.conversionHistoryDao().getById(id) }
            assertNull(persisted)
        } finally {
            runBlocking { database.conversionHistoryDao().deleteById(id) }
        }
    }
}
