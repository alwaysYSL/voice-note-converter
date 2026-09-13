package com.aistudio.voicenote.cvtr.work

import android.net.Uri
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.workDataOf
import com.aistudio.voicenote.cvtr.audio.AudioProcessingOptions

object ConversionWork {
    const val INPUT_URI = "conversion.inputUri"
    const val OUTPUT_FILE_NAME = "conversion.outputFileName"
    const val TRIM_START_MS = "conversion.trimStartMs"
    const val TRIM_END_MS = "conversion.trimEndMs"
    const val PITCH_SEMITONES = "conversion.pitchSemitones"
    const val NORMALIZE_AUDIO = "conversion.normalizeAudio"
    const val TRIM_SILENCE = "conversion.trimSilence"
    const val BATCH_ITEM_ID = "conversion.batchItemId"
    const val SOURCE_FILE_NAME = "conversion.sourceFileName"

    const val PROGRESS = "conversion.progress"
    const val RESULT_URI = "conversion.resultUri"
    const val RESULT_HISTORY_ID = "conversion.resultHistoryId"
    const val RESULT_OUTPUT_FILE_NAME = "conversion.resultOutputFileName"
    const val RESULT_DURATION_SECONDS = "conversion.resultDurationSeconds"
    const val RESULT_WAVEFORM = "conversion.resultWaveform"
    const val RESULT_BITRATE_KBPS = "conversion.resultBitrateKbps"
    const val RESULT_COMPATIBILITY_SUMMARY = "conversion.resultCompatibilitySummary"
    const val RESULT_COMPATIBILITY_WARNING = "conversion.resultCompatibilityWarning"
    const val ERROR_MESSAGE = "conversion.errorMessage"

    const val BATCH_TAG = "conversion.batch"
    const val SINGLE_WORK_NAME = "conversion.single"

    fun request(
        inputUri: Uri,
        requestedOutputName: String?,
        trimStartMs: Long,
        trimEndMs: Long,
        pitchSemitones: Float,
        options: AudioProcessingOptions,
        batchItemId: String? = null,
        sourceFileName: String? = null
    ): OneTimeWorkRequest {
        val input = Data.Builder()
            .putString(INPUT_URI, inputUri.toString())
            .putString(OUTPUT_FILE_NAME, requestedOutputName)
            .putLong(TRIM_START_MS, trimStartMs)
            .putLong(TRIM_END_MS, trimEndMs)
            .putFloat(PITCH_SEMITONES, pitchSemitones)
            .putBoolean(NORMALIZE_AUDIO, options.normalizeAudio)
            .putBoolean(TRIM_SILENCE, options.trimSilence)
            .putString(BATCH_ITEM_ID, batchItemId)
            .putString(SOURCE_FILE_NAME, sourceFileName)
            .build()
        return OneTimeWorkRequestBuilder<ConversionWorker>()
            .setInputData(input)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .apply {
                if (batchItemId != null) {
                    addTag(BATCH_TAG)
                    addTag(batchTag(batchItemId))
                }
            }
            .build()
    }

    fun batchTag(batchItemId: String): String = "conversion.batch.$batchItemId"

    fun options(data: Data): AudioProcessingOptions = AudioProcessingOptions(
        normalizeAudio = data.getBoolean(NORMALIZE_AUDIO, false),
        trimSilence = data.getBoolean(TRIM_SILENCE, false)
    )
}
