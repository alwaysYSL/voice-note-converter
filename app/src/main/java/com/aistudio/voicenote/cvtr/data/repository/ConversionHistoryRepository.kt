package com.aistudio.voicenote.cvtr.data.repository

import androidx.paging.PagingSource
import com.aistudio.voicenote.cvtr.data.local.ConversionHistory
import com.aistudio.voicenote.cvtr.data.local.ConversionHistoryDao
import kotlinx.coroutines.flow.Flow
import com.aistudio.voicenote.cvtr.data.local.HistorySummary

class ConversionHistoryRepository(
    private val dao: ConversionHistoryDao
) {
    fun pagedHistory(
        query: String,
        filter: String,
        sort: String
    ): PagingSource<Int, ConversionHistory> = dao.pagingSource(query, filter, sort)

    fun observeSummary(query: String, filter: String): Flow<HistorySummary> =
        dao.observeSummary(query, filter)

    suspend fun insert(item: ConversionHistory): Long = dao.insert(item)

    suspend fun markShareOpened(
        id: Long,
        target: String,
        openedAt: Long = System.currentTimeMillis()
    ) = dao.markShareOpened(id, target, openedAt)

    suspend fun confirmSent(
        id: Long,
        confirmedAt: Long = System.currentTimeMillis()
    ): Int = dao.confirmSent(id, confirmedAt)

    suspend fun delete(id: Long) = dao.deleteById(id)

    suspend fun deleteAll() = dao.deleteAll()

    /** Durable lookup used by editor history routes; callers pass only the persisted id. */
    suspend fun getEditorSourceById(id: Long): ConversionHistory? = dao.getById(id)

    suspend fun getById(id: Long): ConversionHistory? = getEditorSourceById(id)
    suspend fun getCreatedBefore(cutoff: Long): List<ConversionHistory> =
        dao.getCreatedBefore(cutoff)
}
