package com.aistudio.voicenote.cvtr.ui

import android.app.Application
import android.os.Looper
import androidx.paging.PagingSource
import androidx.test.core.app.ApplicationProvider
import com.aistudio.voicenote.cvtr.data.local.AppDatabase
import com.aistudio.voicenote.cvtr.data.local.ConversionHistory
import com.aistudio.voicenote.cvtr.data.local.DeliveryStatus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

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
    fun `sql history query applies search status and stable sorting`() = runBlocking {
        val dao = AppDatabase.getDatabase(app).conversionHistoryDao()
        insertHistory(id = 101, originalFileName = "Meeting Pagi.m4a", deliveryStatus = DeliveryStatus.CONFIRMED_SENT)
        insertHistory(id = 102, originalFileName = "Ide Konten.m4a")
        insertHistory(id = 103, originalFileName = "Meeting Sore.m4a", deliveryStatus = DeliveryStatus.SHARE_OPENED)
        assertEquals(
            listOf(103L, 101L),
            loadIds(dao, query = "meeting", filter = "ALL")
        )
        assertEquals(
            listOf(101L),
            loadIds(dao, query = "", filter = "CONFIRMED")
        )
        assertEquals(
            listOf(103L, 102L),
            loadIds(dao, query = "", filter = "NOT_CONFIRMED")
        )
    }

    @Test
    fun `summary counts all history independently from list filters`() = runBlocking {
        val dao = AppDatabase.getDatabase(app).conversionHistoryDao()
        insertHistory(id = 103, originalFileName = "Meeting.m4a", fileSizeBytes = 100)
        insertHistory(id = 104, originalFileName = "Ide.m4a", fileSizeBytes = 200)

        val summary = withTimeout(5_000) {
            dao.observeSummary("", "ALL").first { it.count == 2 && it.totalBytes == 300L }
        }

        assertEquals(2, summary.count)
        assertEquals(300L, summary.totalBytes)
        assertEquals(listOf(103L), loadIds(dao, query = "meeting", filter = "ALL"))
    }

    @Test
    fun `bulk deletion removes every selected database record when files are missing`() = runBlocking {
        val firstId = insertHistory(id = 105, outputFilePath = app.filesDir.resolve("missing-105.ogg").absolutePath)
        val secondId = insertHistory(id = 106, outputFilePath = app.filesDir.resolve("missing-106.ogg").absolutePath)
        val viewModel = HistoryViewModel(app)
        val items = loadItems(AppDatabase.getDatabase(app).conversionHistoryDao())

        viewModel.deleteItems(items)
        shadowOf(Looper.getMainLooper()).idle()
        val dao = AppDatabase.getDatabase(app).conversionHistoryDao()
        withTimeout(5_000) {
            while (true) {
                shadowOf(Looper.getMainLooper()).idle()
                if (dao.getById(firstId) == null && dao.getById(secondId) == null) break
                kotlinx.coroutines.delay(25)
            }
        }

        assertNull(dao.getById(firstId))
        assertNull(dao.getById(secondId))
    }

    private suspend fun loadIds(
        dao: com.aistudio.voicenote.cvtr.data.local.ConversionHistoryDao,
        query: String,
        filter: String,
        sort: String = "NEWEST"
    ): List<Long> = loadItems(dao, query, filter, sort).map { it.id }

    private suspend fun loadItems(
        dao: com.aistudio.voicenote.cvtr.data.local.ConversionHistoryDao,
        query: String = "",
        filter: String = "ALL",
        sort: String = "NEWEST"
    ): List<ConversionHistory> {
        val result = dao.pagingSource(query, filter, sort).load(
            PagingSource.LoadParams.Refresh<Int>(
                key = null,
                loadSize = 40,
                placeholdersEnabled = false
            )
        )
        return when (result) {
            is PagingSource.LoadResult.Page -> result.data
            is PagingSource.LoadResult.Error -> throw result.throwable
            is PagingSource.LoadResult.Invalid -> error("History paging source invalid")
        }
    }

    private fun insertHistory(
        id: Long,
        originalFileName: String = "note-$id.m4a",
        outputFilePath: String = "content://voice-note/$id.ogg",
        fileSizeBytes: Long = 256,
        deliveryStatus: DeliveryStatus = DeliveryStatus.READY
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
                deliveryStatus = deliveryStatus
            )
        )
    }

    private fun clearHistory() = runBlocking {
        AppDatabase.getDatabase(app).conversionHistoryDao().deleteAll()
    }
}
