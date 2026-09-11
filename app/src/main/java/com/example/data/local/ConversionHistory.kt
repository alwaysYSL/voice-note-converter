package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversion_history")
data class ConversionHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalFileName: String,
    val outputFileName: String,
    val outputFilePath: String,
    val durationSeconds: Int,
    val fileSizeBytes: Long,
    val waveform: String,
    val bitrateKbps: Int,
    val trimStartMs: Long? = null,
    val trimEndMs: Long? = null,
    val createdAt: Long,
    val sentTo: String? = null,
    val sentAt: Long? = null,
    val pitchSemitones: Float? = null
)
