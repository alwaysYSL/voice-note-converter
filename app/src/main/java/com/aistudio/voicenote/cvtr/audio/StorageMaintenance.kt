package com.aistudio.voicenote.cvtr.audio

import android.content.Context
import java.io.File

object StorageMaintenance {
    const val CACHE_RETENTION_MS = 7L * 24L * 60L * 60L * 1_000L
    const val FAILED_INPUT_RETENTION_MS = 30L * 24L * 60L * 60L * 1_000L

    data class CleanupSummary(
        val deletedCacheFiles: Int,
        val deletedInputFiles: Int
    )

    fun cleanup(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
        cacheRetentionMs: Long = CACHE_RETENTION_MS,
        inputRetentionMs: Long = FAILED_INPUT_RETENTION_MS
    ): CleanupSummary {
        val cacheFiles = context.cacheDir.listFiles().orEmpty()
        val deletedCacheFiles = cacheFiles
            .filter { it.name.startsWith("import_") || it.name.startsWith("voice_note_") }
            .count { deleteIfOlder(it, nowMs, cacheRetentionMs) }
        val sharedDirectory = File(context.cacheDir, "shared_voice_notes")
        val deletedSharedFiles = sharedDirectory.listFiles()
            .orEmpty()
            .filter(File::isFile)
            .count { deleteIfOlder(it, nowMs, cacheRetentionMs) }
        val deletedInputFiles = MediaInputCache.cleanup(context, nowMs, inputRetentionMs)
        return CleanupSummary(
            deletedCacheFiles = deletedCacheFiles + deletedSharedFiles,
            deletedInputFiles = deletedInputFiles
        )
    }

    private fun deleteIfOlder(file: File, nowMs: Long, maxAgeMs: Long): Boolean =
        file.isFile && nowMs - file.lastModified() >= maxAgeMs && file.delete()
}
