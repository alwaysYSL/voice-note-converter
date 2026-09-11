package com.example.data.repository

import com.example.data.local.RecentContact
import com.example.data.local.RecentContactDao
import kotlinx.coroutines.flow.Flow

class ContactRepository(private val dao: RecentContactDao) {
    val recentContacts: Flow<List<RecentContact>> = dao.getAllRecentContacts()

    suspend fun saveContactUsage(name: String, chatId: Long, username: String? = null, phone: String? = null) {
        val existing = dao.getByChatId(chatId)
        if (existing != null) {
            dao.updateLastUsed(chatId, System.currentTimeMillis())
        } else {
            val colors = listOf("#FF7B54", "#2F54EB", "#38A169", "#5C7CFA", "#E07A5F", "#4A6B82")
            val color = colors[(((chatId xor name.hashCode().toLong()) and 0x7FFFFFFFL) % colors.size).toInt()]
            dao.insertContact(
                RecentContact(
                    chatId = chatId,
                    name = name,
                    username = username,
                    phone = phone,
                    avatarColorHex = color,
                    lastUsedAt = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun deleteContact(id: Long) {
        dao.deleteContactById(id)
    }
}
