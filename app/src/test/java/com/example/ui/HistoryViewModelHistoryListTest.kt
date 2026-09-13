package com.example.ui

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.ConversionHistory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryViewModelHistoryListTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        clearHistory()
    }

    @After
    fun tearDown() {
        clearHistory()
    }

    @Test
    fun `search and filter state expose only matching history items`() = runBlocking {
        val meeting = insertHistory(id = 101, originalFileName = "Meeting Pagi.m4a", sentTo = "Telegram")
        insertHistory(id = 102, originalFileName = "Ide Konten.m4a")
        val viewModel = HistoryViewModel(app)

        withTimeout(5_000) { viewModel.historyItems.first { it.size == 2 } }
        viewModel.updateSearch("meeting")
        val matchingItems: List<ConversionHistory> =
            withTimeout(5_000) { viewModel.historyItems.first { it.size == 1 } }
        assertEquals(listOf(meeting), matchingItems.map { item -> item.id })

        viewModel.updateSearch("")
        viewModel.setFilter(HistoryFilter.NOT_SENT)
        val unsentItems: List<ConversionHistory> =
            withTimeout(5_000) { viewModel.historyItems.first { it.size == 1 } }
        assertEquals(listOf(102L), unsentItems.map { item -> item.id })
    }

    @Test
    fun `summary counts all history even when a search is active`() = runBlocking {
        insertHistory(id = 103, originalFileName = "Meeting.m4a", fileSizeBytes = 100)
        insertHistory(id = 104, originalFileName = "Ide.m4a", fileSizeBytes = 200)
        val viewModel = HistoryViewModel(app)

        withTimeout(5_000) { viewModel.totalHistoryCount.first { it == 2 } }
        withTimeout(5_000) { viewModel.totalHistoryBytes.first { it == 300L } }
        viewModel.updateSearch("meeting")

        assertEquals(2, viewModel.totalHistoryCount.value)
        assertEquals(300L, viewModel.totalHistoryBytes.value)
    }

    @Test
    fun `bulk deletion removes every selected database record when files are missing`() = runBlocking {
        val firstId = insertHistory(id = 105, outputFilePath = app.filesDir.resolve("missing-105.ogg").absolutePath)
        val secondId = insertHistory(id = 106, outputFilePath = app.filesDir.resolve("missing-106.ogg").absolutePath)
        val viewModel = HistoryViewModel(app)
        val items = withTimeout(5_000) { viewModel.historyItems.first { it.size == 2 } }

        viewModel.deleteItems(items)
        val dao = AppDatabase.getDatabase(app).conversionHistoryDao()
        withTimeout(5_000) {
            while (dao.getById(firstId) != null || dao.getById(secondId) != null) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(25)
            }
        }

        assertNull(dao.getById(firstId))
        assertNull(dao.getById(secondId))
    }

    private fun insertHistory(
        id: Long,
        originalFileName: String = "note-$id.m4a",
        outputFilePath: String = "content://voice-note/$id.ogg",
        fileSizeBytes: Long = 256,
        sentTo: String? = null
    ): Long = runBlocking {
        AppDatabase.getDatabase(app).conversionHistoryDao().insert(
            ConversionHistory(
                id = id,
                originalFileName = originalFileName,
                outputFileName = "VN_$id.ogg",
                outputFilePath = outputFilePath,
                durationSeconds = 30,
                fileSizeBytes = fileSizeBytes,
                waveform = "[8,12,10]",
                bitrateKbps = 32,
                createdAt = id,
                sentTo = sentTo
            )
        )
    }

    private fun clearHistory() = runBlocking {
        val dao = AppDatabase.getDatabase(app).conversionHistoryDao()
        dao.getAllHistory().first().forEach { dao.deleteById(it.id) }
    }
}
