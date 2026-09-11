package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentContactDao {
    @Query("SELECT * FROM recent_contacts ORDER BY lastUsedAt DESC LIMIT 20")
    fun getAllRecentContacts(): Flow<List<RecentContact>>

    @Query("SELECT * FROM recent_contacts WHERE chatId = :chatId LIMIT 1")
    suspend fun getByChatId(chatId: Long): RecentContact?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: RecentContact): Long

    @Query("DELETE FROM recent_contacts WHERE id = :id")
    suspend fun deleteContactById(id: Long)

    @Query("UPDATE recent_contacts SET lastUsedAt = :timestamp WHERE chatId = :chatId")
    suspend fun updateLastUsed(chatId: Long, timestamp: Long)
}
