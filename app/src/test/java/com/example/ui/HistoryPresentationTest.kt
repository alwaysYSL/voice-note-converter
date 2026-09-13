package com.example.ui

import com.example.data.local.ConversionHistory
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class HistoryPresentationTest {

    @Test
    fun `search matches original and output filenames case insensitively`() {
        val originalMatch = history(id = 1, originalFileName = "Meeting Pagi.m4a")
        val outputMatch = history(id = 2, originalFileName = "catatan.m4a", outputFileName = "VN_PROYEK.ogg")
        val noMatch = history(id = 3, originalFileName = "lainnya.m4a")

        val result = filterAndSortHistory(
            items = listOf(originalMatch, outputMatch, noMatch),
            query = "proyek",
            filter = HistoryFilter.ALL,
            sort = HistorySort.NEWEST
        )

        assertEquals(listOf(outputMatch), result)
    }

    @Test
    fun `filter returns only sent or unsent items`() {
        val sent = history(id = 1, sentTo = "Telegram")
        val unsent = history(id = 2)

        assertEquals(
            listOf(sent),
            filterAndSortHistory(listOf(unsent, sent), "", HistoryFilter.SENT, HistorySort.NEWEST)
        )
        assertEquals(
            listOf(unsent),
            filterAndSortHistory(listOf(unsent, sent), "", HistoryFilter.NOT_SENT, HistorySort.NEWEST)
        )
    }

    @Test
    fun `sort supports oldest newest name and size`() {
        val newest = history(id = 1, originalFileName = "Zeta", createdAt = 300, fileSizeBytes = 100)
        val oldest = history(id = 2, originalFileName = "Alpha", createdAt = 100, fileSizeBytes = 300)
        val middle = history(id = 3, originalFileName = "Beta", createdAt = 200, fileSizeBytes = 200)
        val items = listOf(newest, oldest, middle)

        assertEquals(listOf(newest, middle, oldest), filterAndSortHistory(items, "", HistoryFilter.ALL, HistorySort.NEWEST))
        assertEquals(listOf(oldest, middle, newest), filterAndSortHistory(items, "", HistoryFilter.ALL, HistorySort.OLDEST))
        assertEquals(listOf(oldest, middle, newest), filterAndSortHistory(items, "", HistoryFilter.ALL, HistorySort.NAME))
        assertEquals(listOf(oldest, middle, newest), filterAndSortHistory(items, "", HistoryFilter.ALL, HistorySort.SIZE))
    }

    @Test
    fun `grouping returns relative date sections in display order`() {
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val yesterday = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, -1)
        }.timeInMillis
        val older = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, -8)
        }.timeInMillis

        val sections = groupHistoryItems(
            listOf(
                history(id = 1, createdAt = older),
                history(id = 2, createdAt = now),
                history(id = 3, createdAt = yesterday)
            ),
            now = now
        )

        assertEquals(listOf("Hari ini", "Kemarin", "Lebih lama"), sections.map { it.title })
        assertEquals(listOf(2L), sections[0].items.map { it.id })
        assertEquals(listOf(3L), sections[1].items.map { it.id })
        assertEquals(listOf(1L), sections[2].items.map { it.id })
    }

    private fun history(
        id: Long,
        originalFileName: String = "note-$id.m4a",
        outputFileName: String = "VN_$id.ogg",
        createdAt: Long = id,
        fileSizeBytes: Long = 256,
        sentTo: String? = null
    ) = ConversionHistory(
        id = id,
        originalFileName = originalFileName,
        outputFileName = outputFileName,
        outputFilePath = "content://voice-note/$id.ogg",
        durationSeconds = 30,
        fileSizeBytes = fileSizeBytes,
        waveform = "[8,12,10]",
        bitrateKbps = 32,
        createdAt = createdAt,
        sentTo = sentTo
    )
}
