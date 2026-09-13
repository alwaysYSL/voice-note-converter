package com.example.ui

import com.example.data.local.ConversionHistory
import java.util.Calendar
import java.util.Locale

enum class HistoryFilter {
    ALL,
    SENT,
    NOT_SENT
}

enum class HistorySort {
    NEWEST,
    OLDEST,
    NAME,
    SIZE
}

data class HistorySection(
    val title: String,
    val items: List<ConversionHistory>
)

fun filterAndSortHistory(
    items: List<ConversionHistory>,
    query: String,
    filter: HistoryFilter,
    sort: HistorySort
): List<ConversionHistory> {
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    val filtered = items.filter { item ->
        val matchesQuery = normalizedQuery.isBlank() ||
            item.originalFileName.lowercase(Locale.ROOT).contains(normalizedQuery) ||
            item.outputFileName.lowercase(Locale.ROOT).contains(normalizedQuery)
        val matchesFilter = when (filter) {
            HistoryFilter.ALL -> true
            HistoryFilter.SENT -> item.sentTo != null
            HistoryFilter.NOT_SENT -> item.sentTo == null
        }
        matchesQuery && matchesFilter
    }

    return when (sort) {
        HistorySort.NEWEST -> filtered.sortedWith(
            compareByDescending<ConversionHistory> { it.createdAt }
                .thenByDescending { it.id }
        )
        HistorySort.OLDEST -> filtered.sortedWith(
            compareBy<ConversionHistory> { it.createdAt }
                .thenBy { it.id }
        )
        HistorySort.NAME -> filtered.sortedWith(
            compareBy<ConversionHistory> { it.originalFileName.lowercase(Locale.ROOT) }
                .thenByDescending { it.createdAt }
                .thenByDescending { it.id }
        )
        HistorySort.SIZE -> filtered.sortedWith(
            compareByDescending<ConversionHistory> { it.fileSizeBytes }
                .thenByDescending { it.createdAt }
                .thenByDescending { it.id }
        )
    }
}

fun groupHistoryItems(
    items: List<ConversionHistory>,
    now: Long = System.currentTimeMillis()
): List<HistorySection> {
    val grouped = items.groupBy { historySectionTitle(it.createdAt, now) }
    return HISTORY_SECTION_ORDER.mapNotNull { title ->
        grouped[title]?.let { sectionItems -> HistorySection(title, sectionItems) }
    }
}

private val HISTORY_SECTION_ORDER = listOf("Hari ini", "Kemarin", "Minggu ini", "Lebih lama")

internal fun historySectionTitle(createdAt: Long, now: Long = System.currentTimeMillis()): String {
    val created = Calendar.getInstance().apply { timeInMillis = createdAt }
    val reference = Calendar.getInstance().apply { timeInMillis = now }
    if (created.isSameDay(reference)) return "Hari ini"

    val yesterday = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, -1)
    }
    if (created.isSameDay(yesterday)) return "Kemarin"

    val startOfWeek = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        val daysSinceMonday = (get(Calendar.DAY_OF_WEEK) + 5) % 7
        add(Calendar.DAY_OF_YEAR, -daysSinceMonday)
    }
    return if (created.timeInMillis >= startOfWeek.timeInMillis) "Minggu ini" else "Lebih lama"
}

private fun Calendar.isSameDay(other: Calendar): Boolean =
    get(Calendar.ERA) == other.get(Calendar.ERA) &&
        get(Calendar.YEAR) == other.get(Calendar.YEAR) &&
        get(Calendar.DAY_OF_YEAR) == other.get(Calendar.DAY_OF_YEAR)
