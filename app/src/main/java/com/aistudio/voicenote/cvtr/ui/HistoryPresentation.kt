package com.aistudio.voicenote.cvtr.ui

import java.util.Calendar

enum class HistoryFilter {
    ALL,
    CONFIRMED,
    NOT_CONFIRMED
}

enum class HistorySort {
    NEWEST,
    OLDEST,
    NAME,
    SIZE
}


internal fun HistoryFilter.databaseValue(): String = when (this) {
    HistoryFilter.ALL -> "ALL"
    HistoryFilter.CONFIRMED -> "CONFIRMED"
    HistoryFilter.NOT_CONFIRMED -> "NOT_CONFIRMED"
}

internal fun HistorySort.databaseValue(): String = name


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
