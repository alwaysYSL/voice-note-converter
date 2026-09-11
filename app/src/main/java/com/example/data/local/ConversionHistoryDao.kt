package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversionHistoryDao {
    @Query("SELECT * FROM conversion_history ORDER BY createdAt DESC")
    fun getAllHistory(): Flow<List<ConversionHistory>>

    @Insert
    suspend fun insert(history: ConversionHistory): Long

    @Query("UPDATE conversion_history SET sentTo = :sentTo, sentAt = :sentAt WHERE id = :id")
    suspend fun updateSendStatus(id: Long, sentTo: String, sentAt: Long)

    @Query("DELETE FROM conversion_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM conversion_history WHERE id = :id")
    suspend fun getById(id: Long): ConversionHistory?
}
