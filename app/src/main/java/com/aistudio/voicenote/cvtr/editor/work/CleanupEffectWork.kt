package com.aistudio.voicenote.cvtr.editor.work

import androidx.work.Constraints
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import com.aistudio.voicenote.cvtr.editor.audio.CleanupStrength

internal object CleanupEffectWork {
    const val SOURCE_URI = "cleanup.sourceUri"
    const val SOURCE_FINGERPRINT = "cleanup.sourceFingerprint"
    const val SOURCE_START_MS = "cleanup.sourceStartMs"
    const val SOURCE_END_MS = "cleanup.sourceEndMs"
    const val CLEANUP_STRENGTH = "cleanup.strength"
    const val NORMALIZED = "cleanup.normalized"
    
    const val RESULT_CACHE_KEY_FILENAME = "cleanup.resultCacheKeyFilename"
    const val RESULT_CACHE_KEY_FINGERPRINT = "cleanup.resultCacheKeyFingerprint"
    const val ERROR_MESSAGE = "cleanup.errorMessage"

    fun request(
        sourceUri: String,
        sourceFingerprint: String,
        sourceStartMs: Long,
        sourceEndMs: Long,
        cleanupStrength: CleanupStrength,
        normalized: Boolean,
    ): OneTimeWorkRequest {
        val input = Data.Builder()
            .putString(SOURCE_URI, sourceUri)
            .putString(SOURCE_FINGERPRINT, sourceFingerprint)
            .putLong(SOURCE_START_MS, sourceStartMs)
            .putLong(SOURCE_END_MS, sourceEndMs)
            .putString(CLEANUP_STRENGTH, cleanupStrength.name)
            .putBoolean(NORMALIZED, normalized)
            .build()
            
        return OneTimeWorkRequestBuilder<CleanupEffectWorker>()
            .setInputData(input)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .build()
    }

    fun uniqueWorkName(sourceFingerprint: String, start: Long, end: Long): String {
        return "cleanup.$sourceFingerprint.$start.$end"
    }
}
