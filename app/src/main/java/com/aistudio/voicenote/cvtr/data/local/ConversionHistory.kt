package com.aistudio.voicenote.cvtr.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

enum class DeliveryStatus {
    READY,
    SHARE_OPENED,
    CONFIRMED_SENT
}

class DeliveryStatusConverter {
    @TypeConverter
    fun fromDeliveryStatus(status: DeliveryStatus): String = status.name

    @TypeConverter
    fun toDeliveryStatus(value: String): DeliveryStatus =
        runCatching { DeliveryStatus.valueOf(value) }.getOrDefault(DeliveryStatus.READY)
}

@Entity(
    tableName = "conversion_history",
    indices = [
        Index(value = ["createdAt"]),
        Index(value = ["shareOpenedAt"]),
        Index(value = ["deliveryStatus"]),
        Index(value = ["originalFileName"]),
        Index(value = ["outputFileName"]),
        Index(value = ["editorExportAttemptId"], unique = true),
    ]
)
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
    val deliveryStatus: DeliveryStatus = DeliveryStatus.READY,
    val deliveryTarget: String? = null,
    val shareOpenedAt: Long? = null,
    val confirmedSentAt: Long? = null,
    val pitchSemitones: Float? = null,
    /** Original history row when this item was produced by the editor. */
    val editorSourceHistoryId: Long? = null,
    /** Stable durable idempotency key for an editor export attempt. */
    val editorExportAttemptId: String? = null,
)
