package com.aistudio.voicenote.cvtr.editor.work

import androidx.work.Constraints
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import com.aistudio.voicenote.cvtr.editor.model.ExportPreset
import java.util.UUID

/** WorkManager input/output contract for editor exports. The manifest itself stays on disk. */
internal object EditorExportWork {
    const val MANIFEST_PATH = "editor.manifestPath"
    const val OUTPUT_NAME = "editor.outputName"
    const val PRESET = "editor.preset"
    const val EXPORT_ATTEMPT_ID = "editor.exportAttemptId"

    const val PROGRESS = "editor.progress"
    const val PROGRESS_FRAMES = "editor.progressFrames"
    const val TOTAL_FRAMES = "editor.totalFrames"
    const val RESULT_URI = "editor.resultUri"
    const val RESULT_HISTORY_ID = "editor.resultHistoryId"
    const val RESULT_OUTPUT_NAME = "editor.resultOutputName"
    const val RESULT_OUTPUT_FILE_NAME = RESULT_OUTPUT_NAME
    const val RESULT_DURATION_SECONDS = "editor.resultDurationSeconds"
    const val RESULT_BITRATE_KBPS = "editor.resultBitrateKbps"
    const val ERROR_MESSAGE = "editor.errorMessage"
    const val ERROR_CODE = "editor.errorCode"
    const val ERROR_STAGE = "editor.errorStage"
    const val CAN_RETRY = "editor.canRetry"
    const val LOGICAL_FAILURE = "editor.logicalFailure"
    const val CLEANUP_WARNING = "editor.cleanupWarning"

    fun request(
        manifestPath: String,
        outputName: String?,
        preset: ExportPreset,
        exportAttemptId: String = UUID.randomUUID().toString(),
    ): OneTimeWorkRequest {
        val input = Data.Builder()
            .putString(MANIFEST_PATH, manifestPath)
            .putString(OUTPUT_NAME, outputName)
            .putString(PRESET, preset.name)
            .putString(EXPORT_ATTEMPT_ID, exportAttemptId)
            .build()
        return OneTimeWorkRequestBuilder<EditorExportWorker>()
            .setInputData(input)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .build()
    }

    fun uniqueWorkName(exportAttemptId: String): String = "editor.export.$exportAttemptId"

    fun preset(data: Data): ExportPreset = runCatching {
        ExportPreset.valueOf(data.getString(PRESET).orEmpty())
    }.getOrDefault(ExportPreset.VOICE_NOTE_32)
}
