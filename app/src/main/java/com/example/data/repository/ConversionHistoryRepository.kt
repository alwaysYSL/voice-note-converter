package com.example.data.repository

import com.example.data.local.ConversionHistory
import com.example.data.local.ConversionHistoryDao

class ConversionHistoryRepository(private val dao: ConversionHistoryDao) {
    val allHistory = dao.getAllHistory()

    suspend fun insert(item: ConversionHistory) = dao.insert(item)

    suspend fun updateSendStatus(id: Long, name: String) =
        dao.updateSendStatus(id, name, System.currentTimeMillis())

    suspend fun delete(id: Long) = dao.deleteById(id)

    suspend fun getById(id: Long) = dao.getById(id)
}
