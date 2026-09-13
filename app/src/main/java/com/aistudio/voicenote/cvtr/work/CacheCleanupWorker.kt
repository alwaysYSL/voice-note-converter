package com.aistudio.voicenote.cvtr.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aistudio.voicenote.cvtr.audio.StorageMaintenance
import java.util.concurrent.TimeUnit

class CacheCleanupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        StorageMaintenance.cleanup(applicationContext)
        return Result.success()
    }
}

object MaintenanceScheduler {
    private const val WORK_NAME = "voice-note-cache-cleanup"

    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CacheCleanupWorker>(1, TimeUnit.DAYS)
                .build()
        )
    }
}
