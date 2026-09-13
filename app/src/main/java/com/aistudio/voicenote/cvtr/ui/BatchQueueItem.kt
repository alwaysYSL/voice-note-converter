package com.aistudio.voicenote.cvtr.ui

import androidx.work.WorkInfo
import com.aistudio.voicenote.cvtr.work.ConversionWork

data class BatchQueueItem(
    val id: String,
    val sourceFileName: String,
    val outputFileName: String,
    val state: WorkInfo.State,
    val progress: Int,
    val errorMessage: String?,
    val inputUri: String,
    val trimStartMs: Long,
    val trimEndMs: Long,
    val pitchSemitones: Float,
    val normalizeAudio: Boolean,
    val trimSilence: Boolean
) {
    val isTerminal: Boolean
        get() = state == WorkInfo.State.SUCCEEDED ||
            state == WorkInfo.State.FAILED ||
            state == WorkInfo.State.CANCELLED
}

internal fun WorkInfo.toBatchQueueItem(metadata: BatchQueueMetadata): BatchQueueItem =
    BatchQueueItem(
        id = metadata.id,
        sourceFileName = metadata.sourceFileName,
        outputFileName = outputData
            .getString(ConversionWork.RESULT_OUTPUT_FILE_NAME)
            .orEmpty()
            .ifBlank { metadata.outputFileName },
        state = state,
        progress = (progress.getFloat(ConversionWork.PROGRESS, 0f) * 100f)
            .toInt()
            .coerceIn(0, 100),
        errorMessage = outputData.getString(ConversionWork.ERROR_MESSAGE),
        inputUri = metadata.inputUri,
        trimStartMs = metadata.trimStartMs,
        trimEndMs = metadata.trimEndMs,
        pitchSemitones = metadata.pitchSemitones,
        normalizeAudio = metadata.normalizeAudio,
        trimSilence = metadata.trimSilence
    )
