package com.aistudio.voicenote.cvtr.ui

import android.content.Context
import java.util.UUID

internal data class BatchQueueMetadata(
    val id: String,
    val workId: UUID,
    val sourceFileName: String,
    val outputFileName: String,
    val inputUri: String,
    val sourceUri: String = inputUri,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = Long.MAX_VALUE,
    val pitchSemitones: Float = 0f,
    val normalizeAudio: Boolean = false,
    val trimSilence: Boolean = false
)

internal class BatchQueueStore(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun all(): List<BatchQueueMetadata> = preferences
        .getStringSet(IDS_KEY, emptySet())
        .orEmpty()
        .mapNotNull(::read)

    fun put(item: BatchQueueMetadata) {
        val ids = preferences.getStringSet(IDS_KEY, emptySet()).orEmpty().toMutableSet()
        ids += item.id
        preferences.edit()
            .putStringSet(IDS_KEY, ids)
            .putString(key(item.id, WORK_ID), item.workId.toString())
            .putString(key(item.id, SOURCE_NAME), item.sourceFileName)
            .putString(key(item.id, OUTPUT_NAME), item.outputFileName)
            .putString(key(item.id, INPUT_URI), item.inputUri)
            .putString(key(item.id, SOURCE_URI), item.sourceUri)
            .putLong(key(item.id, TRIM_START), item.trimStartMs)
            .putLong(key(item.id, TRIM_END), item.trimEndMs)
            .putFloat(key(item.id, PITCH), item.pitchSemitones)
            .putBoolean(key(item.id, NORMALIZE), item.normalizeAudio)
            .putBoolean(key(item.id, TRIM_SILENCE), item.trimSilence)
            .apply()
    }


    fun remove(itemId: String) {
        val ids = preferences.getStringSet(IDS_KEY, emptySet()).orEmpty().toMutableSet()
        ids -= itemId
        preferences.edit()
            .putStringSet(IDS_KEY, ids)
            .remove(key(itemId, WORK_ID))
            .remove(key(itemId, SOURCE_NAME))
            .remove(key(itemId, OUTPUT_NAME))
            .remove(key(itemId, INPUT_URI))
            .remove(key(itemId, SOURCE_URI))
            .remove(key(itemId, TRIM_START))
            .remove(key(itemId, TRIM_END))
            .remove(key(itemId, PITCH))
            .remove(key(itemId, NORMALIZE))
            .remove(key(itemId, TRIM_SILENCE))
            .apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun read(id: String): BatchQueueMetadata? {
        val workId = preferences.getString(key(id, WORK_ID), null)
            ?.let { value -> runCatching { UUID.fromString(value) }.getOrNull() }
            ?: return null
        val inputUri = preferences.getString(key(id, INPUT_URI), null) ?: return null
        return BatchQueueMetadata(
            id = id,
            workId = workId,
            sourceFileName = preferences.getString(key(id, SOURCE_NAME), null).orEmpty(),
            outputFileName = preferences.getString(key(id, OUTPUT_NAME), null).orEmpty(),
            inputUri = inputUri,
            sourceUri = preferences.getString(key(id, SOURCE_URI), null) ?: inputUri,
            trimStartMs = preferences.getLong(key(id, TRIM_START), 0L),
            trimEndMs = preferences.getLong(key(id, TRIM_END), Long.MAX_VALUE),
            pitchSemitones = preferences.getFloat(key(id, PITCH), 0f),
            normalizeAudio = preferences.getBoolean(key(id, NORMALIZE), false),
            trimSilence = preferences.getBoolean(key(id, TRIM_SILENCE), false)
        )
    }

    private fun key(id: String, suffix: String): String = "$id.$suffix"

    private companion object {
        const val FILE_NAME = "batch_queue"
        const val IDS_KEY = "ids"
        const val WORK_ID = "workId"
        const val SOURCE_NAME = "sourceName"
        const val OUTPUT_NAME = "outputName"
        const val INPUT_URI = "inputUri"
        const val SOURCE_URI = "sourceUri"
        const val TRIM_START = "trimStart"
        const val TRIM_END = "trimEnd"
        const val PITCH = "pitch"
        const val NORMALIZE = "normalize"
        const val TRIM_SILENCE = "trimSilence"
    }
}
