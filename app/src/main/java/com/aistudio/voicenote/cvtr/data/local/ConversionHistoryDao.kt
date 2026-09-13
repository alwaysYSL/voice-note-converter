package com.aistudio.voicenote.cvtr.data.local

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class HistorySummary(
    val count: Int,
    val totalBytes: Long
)

@Dao
interface ConversionHistoryDao {
    @Query(
        """
        SELECT * FROM conversion_history
        WHERE (
            :query = '' OR
            LOWER(originalFileName) LIKE '%' || LOWER(:query) || '%' OR
            LOWER(outputFileName) LIKE '%' || LOWER(:query) || '%'
        )
        AND (
            :filter = 'ALL' OR
            (:filter = 'CONFIRMED' AND deliveryStatus = 'CONFIRMED_SENT') OR
            (:filter = 'NOT_CONFIRMED' AND deliveryStatus != 'CONFIRMED_SENT')
        )
        ORDER BY
            CASE WHEN :sort = 'NEWEST' THEN createdAt END DESC,
            CASE WHEN :sort = 'OLDEST' THEN createdAt END ASC,
            CASE WHEN :sort = 'NAME' THEN LOWER(originalFileName) END ASC,
            CASE WHEN :sort = 'SIZE' THEN fileSizeBytes END DESC,
            CASE WHEN :sort = 'NEWEST' THEN id END DESC,
            CASE WHEN :sort = 'OLDEST' THEN id END ASC,
            CASE WHEN :sort = 'NAME' THEN createdAt END DESC,
            CASE WHEN :sort = 'NAME' THEN id END DESC,
            CASE WHEN :sort = 'SIZE' THEN createdAt END DESC,
            CASE WHEN :sort = 'SIZE' THEN id END DESC
        """
    )
    fun pagingSource(
        query: String,
        filter: String,
        sort: String
    ): PagingSource<Int, ConversionHistory>

    @Query(
        """
        SELECT
            COUNT(*) AS count,
            COALESCE(SUM(fileSizeBytes), 0) AS totalBytes
        FROM conversion_history
        WHERE (
            :query = '' OR
            LOWER(originalFileName) LIKE '%' || LOWER(:query) || '%' OR
            LOWER(outputFileName) LIKE '%' || LOWER(:query) || '%'
        )
        AND (
            :filter = 'ALL' OR
            (:filter = 'CONFIRMED' AND deliveryStatus = 'CONFIRMED_SENT') OR
            (:filter = 'NOT_CONFIRMED' AND deliveryStatus != 'CONFIRMED_SENT')
        )
        """
    )
    fun observeSummary(query: String, filter: String): Flow<HistorySummary>

    @Insert
    suspend fun insert(history: ConversionHistory): Long

    @Query(
        """
        UPDATE conversion_history
        SET deliveryStatus = 'SHARE_OPENED',
            deliveryTarget = :target,
            shareOpenedAt = :openedAt,
            confirmedSentAt = NULL
        WHERE id = :id
        """
    )
    suspend fun markShareOpened(id: Long, target: String, openedAt: Long)

    @Query(
        """
        UPDATE conversion_history
        SET deliveryStatus = 'CONFIRMED_SENT',
            confirmedSentAt = :confirmedAt
        WHERE id = :id AND deliveryStatus = 'SHARE_OPENED'
        """
    )
    suspend fun confirmSent(id: Long, confirmedAt: Long): Int

    @Query("DELETE FROM conversion_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM conversion_history")
    suspend fun deleteAll()

    @Query("SELECT * FROM conversion_history WHERE id = :id")
    suspend fun getById(id: Long): ConversionHistory?
    @Query(
        "SELECT * FROM conversion_history WHERE createdAt < :cutoff ORDER BY createdAt ASC"
    )
    suspend fun getCreatedBefore(cutoff: Long): List<ConversionHistory>
}
