package com.aistudio.voicenote.cvtr.editor.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aistudio.voicenote.cvtr.data.local.AppDatabase
import com.aistudio.voicenote.cvtr.editor.audio.EditorRenderManifest
import com.aistudio.voicenote.cvtr.editor.cache.ProcessedAudioCache
import com.aistudio.voicenote.cvtr.editor.data.DraftSourceStorage
import com.aistudio.voicenote.cvtr.editor.data.EditorDraftRepository
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Reconciles only the editor's private roots. Draft sources and public history output are not
 * cache data and therefore never participate in eviction.
 */
class EditorMaintenanceWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = try {
        runOnce(applicationContext)
        Result.success()
    } catch (_: Throwable) {
        Result.retry()
    }

    companion object {
        const val WORK_NAME = "editor-maintenance-v1"
        const val RETENTION_MS = 7L * 24L * 60L * 60L * 1_000L

        /** Testable entry point used by the focused maintenance test. */
        internal suspend fun runOnce(
            context: Context,
            nowMs: Long = System.currentTimeMillis(),
            retentionMs: Long = RETENTION_MS,
        ): MaintenanceSummary {
            require(retentionMs >= 0L) { "retentionMs must not be negative" }
            val database = AppDatabase.getDatabase(context)
            val cache = ProcessedAudioCache(File(context.cacheDir, CACHE_DIRECTORY))
            val draftRepository = EditorDraftRepository(
                database = database,
                sourceStorage = DraftSourceStorage(context),
                processedAudioCache = cache,
            )
            // Room is authoritative for draft versions and cache leases. A failed reconciliation
            // is surfaced to WorkManager; no best-effort deletion is attempted in that case.
            draftRepository.reconcileDraftStorage()
            val manifestsRoot = File(context.filesDir, MANIFEST_DIRECTORY).canonicalFile
            val exportRoot = File(context.cacheDir, EXPORT_DIRECTORY).canonicalFile
            val activeAttempts = validManifestAttempts(manifestsRoot)
            val deletedManifests = removeOrphanManifests(manifestsRoot, activeAttempts, nowMs, retentionMs)
            val deletedPartials = removeOrphanExportPartials(exportRoot, activeAttempts, nowMs, retentionMs)
            cache.clearPartials(retentionMs, nowMs)
            val deletedCache = cache.removeUnreferencedOlderThan(retentionMs, nowMs)
            // Read history paths before maintenance as an explicit safety gate. Public files are
            // never deletion targets, but retaining this query makes that invariant auditable.
            database.conversionHistoryDao().getAllOutputFilePaths()
            return MaintenanceSummary(deletedCache, deletedPartials, deletedManifests)
        }

        private fun validManifestAttempts(root: File): Set<String> = root.listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            .mapNotNull { file ->
                runCatching { EditorRenderManifest.readValidated(file).exportAttemptId }.getOrNull()
            }
            .toSet()

        private fun removeOrphanManifests(
            root: File,
            activeAttempts: Set<String>,
            nowMs: Long,
            retentionMs: Long,
        ): Int {
            if (!root.isDirectory) return 0
            val cutoff = nowMs - retentionMs
            return root.listFiles().orEmpty().count { file ->
                val old = file.lastModified() <= cutoff
                val valid = file.isFile && file.extension.equals("json", ignoreCase = true) &&
                    runCatching { EditorRenderManifest.readValidated(file) }.isSuccess
                val orphan = !valid || file.name.endsWith(".tmp") ||
                    (valid && activeAttempts.none { attempt -> sanitize(attempt) + ".json" == file.name })
                // A valid manifest remains a durable owner record until the export worker has
                // published or reported its terminal state. Only malformed/temporary manifests
                // are safe to delete without querying WorkManager's history.
                old && orphan && (!valid || file.name.endsWith(".tmp")) && safeDelete(file, root)
            }
        }

        private fun removeOrphanExportPartials(
            root: File,
            activeAttempts: Set<String>,
            nowMs: Long,
            retentionMs: Long,
        ): Int {
            if (!root.isDirectory) return 0
            val cutoff = nowMs - retentionMs
            return root.listFiles().orEmpty().count { file ->
                val owned = activeAttempts.any { attempt -> file.name.startsWith(sanitize(attempt)) }
                file.isFile && file.name.endsWith(".partial") &&
                    file.lastModified() <= cutoff && !owned && safeDelete(file, root)
            }
        }

        private fun sanitize(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

        private fun safeDelete(file: File, root: File): Boolean =
            runCatching { file.canonicalFile.parentFile == root.canonicalFile && file.delete() }.getOrDefault(false)

        private const val CACHE_DIRECTORY = "processed_audio"
        private const val MANIFEST_DIRECTORY = "editor/manifests"
        private const val EXPORT_DIRECTORY = "editor-export"
    }
}

internal data class MaintenanceSummary(
    val deletedCacheFiles: Int,
    val deletedExportPartials: Int,
    val deletedManifests: Int,
)

object EditorMaintenanceScheduler {
    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiresStorageNotLow(true)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            EditorMaintenanceWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<EditorMaintenanceWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build(),
        )
    }
}
