package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recent_contacts")
data class RecentContact(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val chatId: Long,
    val name: String,
    val username: String? = null,
    val phone: String? = null,
    val avatarColorHex: String = "#0284C7",
    val lastUsedAt: Long = System.currentTimeMillis()
)
