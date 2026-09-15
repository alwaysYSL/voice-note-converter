package com.aistudio.voicenote.cvtr.editor.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.ProgressUpdater
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.taskexecutor.SerialExecutor
import androidx.work.impl.utils.taskexecutor.TaskExecutor
import com.google.common.util.concurrent.Futures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
class CleanupEffectWorkerTest {
    @Test
    fun `worker fails if source uri is missing`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val input = androidx.work.Data.Builder().build()

        val worker = CleanupEffectWorker(context, workerParameters(input))
        val result = worker.doWork()
        assertTrue(result is ListenableWorker.Result.Failure)
    }

    private fun workerParameters(input: Data): WorkerParameters {
        val executor = Executors.newSingleThreadExecutor()
        val serial = object : SerialExecutor {
            override fun execute(command: Runnable) = executor.execute(command)
            override fun hasPendingTasks(): Boolean = false
        }
        val taskExecutor = object : TaskExecutor {
            override fun getMainThreadExecutor(): Executor = executor
            override fun getSerialTaskExecutor(): SerialExecutor = serial
        }
        val workerFactory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker? = null
        }
        val progress = ProgressUpdater { _, _, _ -> Futures.immediateVoidFuture() }
        val foreground = ForegroundUpdater { _, _, _ -> Futures.immediateVoidFuture() }
        return WorkerParameters(
            UUID.randomUUID(),
            input,
            emptyList<String>(),
            WorkerParameters.RuntimeExtras(),
            0,
            0,
            executor,
            kotlinx.coroutines.Dispatchers.Default,
            taskExecutor,
            workerFactory,
            progress,
            foreground,
        )
    }
}
