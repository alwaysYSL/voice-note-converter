package com.aistudio.voicenote.cvtr.ui

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.aistudio.voicenote.cvtr.data.local.AppDatabase
import com.aistudio.voicenote.cvtr.data.local.ConversionHistory
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
    fun `deleteItem removes history when legacy raw-path file is already missing`() = runBlocking {
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
        val id = database.conversionHistoryDao().insert(item)

        try {
            HistoryViewModel(app).deleteItem(item.copy(id = id))
            kotlinx.coroutines.withTimeout(5_000) {
                while (true) {
                    shadowOf(Looper.getMainLooper()).idle()
                    if (database.conversionHistoryDao().getById(id) == null) break
                    kotlinx.coroutines.delay(25)
                }
            }
            assertNull(database.conversionHistoryDao().getById(id))
        } finally {
            database.conversionHistoryDao().deleteById(id)
        }
    }
}
