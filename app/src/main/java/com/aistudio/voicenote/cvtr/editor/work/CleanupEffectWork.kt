package com.aistudio.voicenote.cvtr.editor.work

import androidx.work.Constraints
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import com.aistudio.voicenote.cvtr.editor.audio.CleanupStrength
import com.aistudio.voicenote.cvtr.editor.cache.ProcessedAudioCache

internal object CleanupEffectWork {
    const val SOURCE_URI = "cleanup.sourceUri"
    const val SOURCE_FINGERPRINT = "cleanup.sourceFingerprint"
    const val SOURCE_START_MS = "cleanup.sourceStartMs"
    const val SOURCE_END_MS = "cleanup.sourceEndMs"
    const val CLEANUP_STRENGTH = "cleanup.strength"
    const val NORMALIZED = "cleanup.normalized"
    const val ALGORITHM_VERSION = "cleanup.algorithmVersion"
    
    const val RESULT_CACHE_KEY_FILENAME = "cleanup.resultCacheKeyFilename"
    const val RESULT_CACHE_KEY_FINGERPRINT = "cleanup.resultCacheKeyFingerprint"
    const val RESULT_SOURCE_START_MS = "cleanup.resultSourceStartMs"
    const val RESULT_SOURCE_END_MS = "cleanup.resultSourceEndMs"
    const val RESULT_CLEANUP_STRENGTH = "cleanup.resultStrength"
    const val RESULT_NORMALIZED = "cleanup.resultNormalized"
    const val RESULT_ALGORITHM_VERSION = "cleanup.resultAlgorithmVersion"
    const val ERROR_MESSAGE = "cleanup.errorMessage"
    const val PROGRESS = "cleanup.progress"
    const val PROGRESS_FRAMES = "cleanup.progressFrames"
    const val TOTAL_FRAMES = "cleanup.totalFrames"

    fun request(
        sourceUri: String,
        sourceFingerprint: String,
        sourceStartMs: Long,
        sourceEndMs: Long,
        cleanupStrength: CleanupStrength,
        normalized: Boolean,
        algorithmVersion: String = ProcessedAudioCache.CACHE_ALGORITHM_VERSION,
    ): OneTimeWorkRequest {
        val input = Data.Builder()
            .putString(SOURCE_URI, sourceUri)
            .putString(SOURCE_FINGERPRINT, sourceFingerprint)
            .putLong(SOURCE_START_MS, sourceStartMs)
            .putLong(SOURCE_END_MS, sourceEndMs)
            .putString(CLEANUP_STRENGTH, cleanupStrength.name)
            .putBoolean(NORMALIZED, normalized)
            .putString(ALGORITHM_VERSION, algorithmVersion)
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

    fun uniqueWorkName(
        sourceFingerprint: String,
        start: Long,
        end: Long,
        cleanupStrength: CleanupStrength = CleanupStrength.OFF,
        normalized: Boolean = false,
        algorithmVersion: String = ProcessedAudioCache.CACHE_ALGORITHM_VERSION,
        attemptIdentity: String = "shared",
    ): String {
        return "cleanup.$sourceFingerprint.$start.$end.${cleanupStrength.name}.$normalized.$algorithmVersion.$attemptIdentity"
    }
}
